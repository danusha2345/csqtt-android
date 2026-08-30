// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use crate::{App, protocol};
use anyhow::{Result, bail};
use std::{
    net::{IpAddr, SocketAddr},
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpStream, lookup_host},
    sync::mpsc,
};

pub const MAGIC: &[u8; 6] = b"CSQPX1";
const HEADER_LEN: usize = MAGIC.len() + 1 + 8;
const MAX_DATA: usize = 2_000;
const MAX_GLOBAL_STREAMS: usize = 256;
const MAX_SESSION_STREAMS: usize = 32;
const OPEN: u8 = 1;
const OPEN_OK: u8 = 2;
const OPEN_ERR: u8 = 3;
const DATA: u8 = 4;
const CLOSE: u8 = 5;

#[derive(Debug)]
pub enum StreamInput {
    Data(Vec<u8>),
    Close,
}

struct Frame<'a> {
    kind: u8,
    stream_id: u64,
    payload: &'a [u8],
}

pub fn is_frame(payload: &[u8]) -> bool {
    payload.len() >= HEADER_LEN && payload.starts_with(MAGIC)
}

fn parse_frame(payload: &[u8]) -> Option<Frame<'_>> {
    if !is_frame(payload) {
        return None;
    }
    Some(Frame {
        kind: payload[6],
        stream_id: u64::from_be_bytes(payload[7..15].try_into().ok()?),
        payload: &payload[HEADER_LEN..],
    })
}

fn encode_frame(kind: u8, stream_id: u64, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(HEADER_LEN + payload.len());
    frame.extend_from_slice(MAGIC);
    frame.push(kind);
    frame.extend_from_slice(&stream_id.to_be_bytes());
    frame.extend_from_slice(payload);
    frame
}

fn parse_target(payload: &[u8]) -> Result<(String, u16)> {
    let (&kind, rest) = payload
        .split_first()
        .ok_or_else(|| anyhow::anyhow!("missing address type"))?;
    let (host, port_bytes) = match kind {
        1 if rest.len() == 6 => (
            std::net::Ipv4Addr::new(rest[0], rest[1], rest[2], rest[3]).to_string(),
            &rest[4..],
        ),
        4 if rest.len() == 18 => (
            std::net::Ipv6Addr::from(<[u8; 16]>::try_from(&rest[..16])?).to_string(),
            &rest[16..],
        ),
        3 if !rest.is_empty() => {
            let length = rest[0] as usize;
            if length == 0 || rest.len() != 1 + length + 2 {
                bail!("invalid domain target");
            }
            let host = std::str::from_utf8(&rest[1..1 + length])?.to_owned();
            if host.chars().any(char::is_control) {
                bail!("invalid domain target");
            }
            (host, &rest[1 + length..])
        }
        _ => bail!("unsupported target address"),
    };
    let port = u16::from_be_bytes(port_bytes.try_into()?);
    if port == 0 {
        bail!("invalid target port");
    }
    Ok((host, port))
}

fn allowed_destination(address: IpAddr) -> bool {
    match address {
        IpAddr::V4(ip) => {
            !ip.is_unspecified()
                && !ip.is_loopback()
                && !ip.is_link_local()
                && !ip.is_private()
                && !ip.is_multicast()
                && ip.octets() != [255, 255, 255, 255]
        }
        IpAddr::V6(ip) => {
            if let Some(mapped) = ip.to_ipv4_mapped() {
                return allowed_destination(IpAddr::V4(mapped));
            }
            !ip.is_unspecified()
                && !ip.is_loopback()
                && !ip.is_multicast()
                && !(ip.segments()[0] & 0xffc0 == 0xfe80)
                && !(ip.segments()[0] & 0xfe00 == 0xfc00)
        }
    }
}

async fn resolve_target(host: &str, port: u16) -> Result<SocketAddr> {
    let mut addresses = tokio::time::timeout(Duration::from_secs(10), lookup_host((host, port)))
        .await
        .map_err(|_| anyhow::anyhow!("DNS timeout"))??;
    addresses
        .find(|address| allowed_destination(address.ip()))
        .ok_or_else(|| anyhow::anyhow!("target is not a public address"))
}

fn send_frame(app: &Arc<App>, session_id: u64, frame: Vec<u8>) -> Result<()> {
    protocol::command(
        app,
        protocol::ProtocolCommand::SendPlain {
            session_id,
            payload: frame,
        },
    )
}

pub async fn handle_frame(app: &Arc<App>, session_id: u64, payload: &[u8]) -> Result<()> {
    let frame = parse_frame(payload).ok_or_else(|| anyhow::anyhow!("invalid proxy frame"))?;
    let key = (session_id, frame.stream_id);
    match frame.kind {
        OPEN => {
            let session_count = app
                .proxy_streams
                .iter()
                .filter(|entry| entry.key().0 == session_id)
                .count();
            if app.proxy_streams.len() >= MAX_GLOBAL_STREAMS
                || session_count >= MAX_SESSION_STREAMS
                || app.proxy_streams.contains_key(&key)
            {
                send_frame(
                    app,
                    session_id,
                    encode_frame(OPEN_ERR, frame.stream_id, &[1]),
                )?;
                return Ok(());
            }
            let target = match parse_target(frame.payload) {
                Ok(target) => target,
                Err(_) => {
                    send_frame(
                        app,
                        session_id,
                        encode_frame(OPEN_ERR, frame.stream_id, &[8]),
                    )?;
                    return Ok(());
                }
            };
            let (tx, rx) = mpsc::channel(64);
            app.proxy_streams.insert(key, tx);
            let app = app.clone();
            tokio::spawn(run_stream(app, session_id, frame.stream_id, target, rx));
        }
        DATA => {
            if frame.payload.len() > MAX_DATA {
                bail!("proxy data frame too large");
            }
            if let Some(sender) = app.proxy_streams.get(&key) {
                let _ = sender.try_send(StreamInput::Data(frame.payload.to_vec()));
            }
        }
        CLOSE => {
            if let Some((_, sender)) = app.proxy_streams.remove(&key) {
                let _ = sender.try_send(StreamInput::Close);
            }
        }
        _ => bail!("unexpected client proxy frame"),
    }
    Ok(())
}

async fn run_stream(
    app: Arc<App>,
    session_id: u64,
    stream_id: u64,
    target: (String, u16),
    mut input: mpsc::Receiver<StreamInput>,
) {
    let result = async {
        let address = resolve_target(&target.0, target.1).await?;
        let mut stream = tokio::time::timeout(Duration::from_secs(15), TcpStream::connect(address))
            .await.map_err(|_| anyhow::anyhow!("connect timeout"))??;
        stream.set_nodelay(true)?;
        send_frame(&app, session_id, encode_frame(OPEN_OK, stream_id, &[]))?;
        let mut buffer = vec![0u8; MAX_DATA];
        loop {
            tokio::select! {
                read = stream.read(&mut buffer) => match read? {
                    0 => break,
                    length => send_frame(&app, session_id, encode_frame(DATA, stream_id, &buffer[..length]))?,
                },
                command = input.recv() => match command {
                    Some(StreamInput::Data(data)) => stream.write_all(&data).await?,
                    Some(StreamInput::Close) | None => break,
                },
            }
        }
        Result::<()>::Ok(())
    }.await;
    app.proxy_streams.remove(&(session_id, stream_id));
    if result.is_err() {
        let _ = send_frame(&app, session_id, encode_frame(OPEN_ERR, stream_id, &[5]));
    }
    let _ = send_frame(&app, session_id, encode_frame(CLOSE, stream_id, &[]));
}

pub fn close_session(app: &Arc<App>, session_id: u64) {
    let keys: Vec<_> = app
        .proxy_streams
        .iter()
        .filter_map(|entry| (entry.key().0 == session_id).then_some(*entry.key()))
        .collect();
    for key in keys {
        if let Some((_, sender)) = app.proxy_streams.remove(&key) {
            let _ = sender.try_send(StreamInput::Close);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn target_parser_accepts_domain_and_rejects_zero_port() {
        let mut payload = vec![3, 11];
        payload.extend_from_slice(b"example.com");
        payload.extend_from_slice(&443u16.to_be_bytes());
        assert_eq!(
            parse_target(&payload).unwrap(),
            ("example.com".to_owned(), 443)
        );
        let length = payload.len();
        payload[length - 2..].copy_from_slice(&0u16.to_be_bytes());
        assert!(parse_target(&payload).is_err());
    }

    #[test]
    fn private_and_metadata_destinations_are_blocked() {
        assert!(!allowed_destination("127.0.0.1".parse().unwrap()));
        assert!(!allowed_destination("10.0.0.1".parse().unwrap()));
        assert!(!allowed_destination("169.254.169.254".parse().unwrap()));
        assert!(!allowed_destination("::ffff:127.0.0.1".parse().unwrap()));
        assert!(allowed_destination("1.1.1.1".parse().unwrap()));
    }
}
