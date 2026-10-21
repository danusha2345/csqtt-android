// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use crate::{dispatcher::Dispatcher, packet::PacketPool};
use anyhow::{Context, Result, bail};
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::{Mutex, Semaphore, mpsc},
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;

pub const MAGIC: &[u8; 6] = b"CSQPX2";
const HEADER_LEN: usize = MAGIC.len() + 1 + 8;
const MAX_DATA: usize = 1_100;
use crate::proxy_sequence::StreamSequence;
const MAX_STREAMS: usize = 64;
pub(crate) const OPEN: u8 = 1;
const OPEN_OK: u8 = 2;
const OPEN_ERR: u8 = 3;
const DATA: u8 = 4;
pub(crate) const CLOSE: u8 = 5;

#[derive(Debug)]
enum Inbound {
    Opened,
    Error(u8),
    Data(Vec<u8>),
    Closed,
}

struct Frame<'a> {
    kind: u8,
    stream_id: u64,
    payload: &'a [u8],
}

pub fn is_frame(payload: &[u8]) -> bool {
    payload.len() >= HEADER_LEN && payload.starts_with(MAGIC)
}

pub(crate) fn frame_route(payload: &[u8]) -> Option<(u8, u64)> {
    parse_frame(payload).map(|frame| (frame.kind, frame.stream_id))
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

fn deliver_inbound(streams: &mut HashMap<u64, mpsc::Sender<Inbound>>, id: u64, event: Inbound) {
    if let Some(sender) = streams.get(&id)
        && sender.try_send(event).is_err()
    {
        // Close after the queued prefix; never deliver bytes following the lost frame.
        streams.remove(&id);
    }
}

fn validate_bind(bind: &str, username: &str, password: &str) -> Result<SocketAddr> {
    let requested: SocketAddr = bind.parse().context("invalid SOCKS5 bind address")?;
    if !requested.ip().is_loopback() && (username.is_empty() || password.is_empty()) {
        bail!("LAN SOCKS5 requires a username and password");
    }
    if username.len() > 255 || password.len() > 255 {
        bail!("SOCKS5 credentials exceed 255 bytes");
    }
    Ok(requested)
}

pub async fn start(
    bind: &str,
    username: &str,
    password: &str,
    dispatcher: Arc<Dispatcher>,
    pool: Arc<PacketPool>,
    cancel: CancellationToken,
) -> Result<(SocketAddr, JoinHandle<()>)> {
    let requested = validate_bind(bind, username, password)?;
    let auth = Arc::new((username.to_owned(), password.to_owned()));
    let listener = TcpListener::bind(requested)
        .await
        .context("SOCKS5 bind failed")?;
    let address = listener.local_addr()?;
    let streams = Arc::new(Mutex::new(HashMap::<u64, mpsc::Sender<Inbound>>::new()));
    let (frame_tx, mut frame_rx) = mpsc::channel::<Vec<u8>>(512);
    dispatcher.set_proxy_frame_sender(frame_tx)?;
    let inbound_streams = streams.clone();
    let inbound_cancel = cancel.clone();
    tokio::spawn(async move {
        loop {
            let payload = tokio::select! {
                _ = inbound_cancel.cancelled() => return,
                payload = frame_rx.recv() => match payload { Some(payload) => payload, None => return },
            };
            let Some(frame) = parse_frame(&payload) else {
                continue;
            };
            let event = match frame.kind {
                OPEN_OK => Inbound::Opened,
                OPEN_ERR => Inbound::Error(frame.payload.first().copied().unwrap_or(1)),
                DATA if frame.payload.len() <= MAX_DATA + 8 => {
                    Inbound::Data(frame.payload.to_vec())
                }
                CLOSE => Inbound::Closed,
                _ => continue,
            };
            let mut streams = inbound_streams.lock().await;
            deliver_inbound(&mut streams, frame.stream_id, event);
        }
    });

    let task = tokio::spawn(async move {
        let ids = AtomicU64::new(1);
        let slots = Arc::new(Semaphore::new(MAX_STREAMS));
        loop {
            let accepted = tokio::select! {
                _ = cancel.cancelled() => return,
                accepted = listener.accept() => accepted,
            };
            let Ok((socket, _)) = accepted else { continue };
            if streams.lock().await.len() >= MAX_STREAMS {
                drop(socket);
                continue;
            }
            let Ok(slot) = slots.clone().try_acquire_owned() else {
                drop(socket);
                continue;
            };
            let id = ids.fetch_add(1, Ordering::Relaxed).max(1);
            let dispatcher = dispatcher.clone();
            let pool = pool.clone();
            let streams = streams.clone();
            let cancel = cancel.clone();
            let auth = auth.clone();
            tokio::spawn(async move {
                let _slot = slot;
                let _ = handle_client(socket, id, dispatcher, pool, streams, cancel, auth).await;
            });
        }
    });
    Ok((address, task))
}

async fn handle_client(
    mut socket: TcpStream,
    stream_id: u64,
    dispatcher: Arc<Dispatcher>,
    pool: Arc<PacketPool>,
    streams: Arc<Mutex<HashMap<u64, mpsc::Sender<Inbound>>>>,
    cancel: CancellationToken,
    auth: Arc<(String, String)>,
) -> Result<()> {
    socket.set_nodelay(true)?;
    let target = tokio::time::timeout(Duration::from_secs(10), read_handshake(&mut socket, &auth))
        .await
        .context("SOCKS5 handshake timed out")??;
    let (tx, mut rx) = mpsc::channel(64);
    streams.lock().await.insert(stream_id, tx);
    let result = async {
        dispatcher.send_proxy_frame(&pool, &encode_frame(OPEN, stream_id, &target)).await?;
        let opened = tokio::time::timeout(Duration::from_secs(20), rx.recv()).await;
        match opened {
            Ok(Some(Inbound::Opened)) => write_reply(&mut socket, 0).await?,
            Ok(Some(Inbound::Error(code))) => {
                write_reply(&mut socket, code).await?;
                bail!("remote SOCKS5 connect failed");
            }
            _ => {
                write_reply(&mut socket, 4).await?;
                bail!("remote SOCKS5 connect timed out");
            }
        }
        let (mut reader, mut writer) = socket.into_split();
        let mut buffer = vec![0u8; MAX_DATA];
        let mut sent = StreamSequence::default();
        let mut received = StreamSequence::default();
        loop {
            tokio::select! {
                _ = cancel.cancelled() => break,
                read = reader.read(&mut buffer) => match read? {
                    0 => break,
                    length => dispatcher.send_proxy_frame(&pool, &encode_frame(DATA, stream_id, &sent.encode(&buffer[..length])?)).await?,
                },
                inbound = rx.recv() => match inbound {
                    Some(Inbound::Data(data)) => writer.write_all(received.decode(&data)?).await?,
                    Some(Inbound::Closed | Inbound::Error(_)) | None => break,
                    Some(Inbound::Opened) => {}
                },
            }
        }
        Result::<()>::Ok(())
    }.await;
    streams.lock().await.remove(&stream_id);
    let _ = dispatcher
        .send_proxy_frame(&pool, &encode_frame(CLOSE, stream_id, &[]))
        .await;
    result
}

async fn read_handshake(socket: &mut TcpStream, auth: &(String, String)) -> Result<Vec<u8>> {
    let mut greeting = [0u8; 2];
    socket.read_exact(&mut greeting).await?;
    if greeting[0] != 5 || greeting[1] == 0 {
        bail!("invalid SOCKS5 greeting");
    }
    let mut methods = vec![0u8; greeting[1] as usize];
    socket.read_exact(&mut methods).await?;
    let method = if auth.0.is_empty() && auth.1.is_empty() {
        0
    } else {
        2
    };
    if !methods.contains(&method) {
        socket.write_all(&[5, 0xff]).await?;
        bail!("SOCKS5 authentication method unavailable");
    }
    socket.write_all(&[5, method]).await?;
    if method == 2 {
        let version = socket.read_u8().await?;
        let n = socket.read_u8().await? as usize;
        let mut username = vec![0; n];
        socket.read_exact(&mut username).await?;
        let n = socket.read_u8().await? as usize;
        let mut password = vec![0; n];
        socket.read_exact(&mut password).await?;
        use subtle::ConstantTimeEq;
        let accepted = version == 1
            && bool::from(username.as_slice().ct_eq(auth.0.as_bytes()))
            && bool::from(password.as_slice().ct_eq(auth.1.as_bytes()));
        socket.write_all(&[1, u8::from(!accepted)]).await?;
        if !accepted {
            bail!("SOCKS5 authentication failed");
        }
    }
    let mut request = [0u8; 4];
    socket.read_exact(&mut request).await?;
    if request[0] != 5 || request[1] != 1 || request[2] != 0 {
        write_reply(socket, 7).await?;
        bail!("only SOCKS5 CONNECT is supported");
    }
    let mut target = vec![request[3]];
    match request[3] {
        1 => {
            let mut rest = [0u8; 6];
            socket.read_exact(&mut rest).await?;
            target.extend_from_slice(&rest);
        }
        4 => {
            let mut rest = [0u8; 18];
            socket.read_exact(&mut rest).await?;
            target.extend_from_slice(&rest);
        }
        3 => {
            let length = socket.read_u8().await? as usize;
            if length == 0 {
                bail!("empty SOCKS5 domain");
            }
            target.push(length as u8);
            let mut rest = vec![0u8; length + 2];
            socket.read_exact(&mut rest).await?;
            target.extend_from_slice(&rest);
        }
        _ => {
            write_reply(socket, 8).await?;
            bail!("unsupported SOCKS5 address type");
        }
    }
    Ok(target)
}

async fn write_reply(socket: &mut TcpStream, code: u8) -> Result<()> {
    socket.write_all(&[5, code, 0, 1, 0, 0, 0, 0, 0, 0]).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn lan_requires_explicit_credentials() {
        assert!(validate_bind("127.0.0.1:1080", "", "").is_ok());
        assert!(validate_bind("0.0.0.0:1080", "", "").is_err());
        assert!(validate_bind("0.0.0.0:1080", "user", "").is_err());
        assert!(validate_bind("0.0.0.0:1080", "user", "password").is_ok());
    }
    async fn attempt(password: &[u8]) -> (Vec<u8>, Result<Vec<u8>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            read_handshake(&mut socket, &("user".into(), "password".into())).await
        });
        let mut client = TcpStream::connect(address).await.unwrap();
        client.write_all(&[5, 1, 2]).await.unwrap();
        let mut reply = [0; 2];
        client.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply, [5, 2]);
        let mut auth = vec![1, 4];
        auth.extend_from_slice(b"user");
        auth.push(password.len() as u8);
        auth.extend_from_slice(password);
        client.write_all(&auth).await.unwrap();
        client.read_exact(&mut reply).await.unwrap();
        if reply == [1, 0] {
            client
                .write_all(&[5, 1, 0, 1, 8, 8, 8, 8, 1, 187])
                .await
                .unwrap();
        }
        (reply.to_vec(), server.await.unwrap())
    }
    #[tokio::test]
    async fn authenticated_connect_preserves_the_requested_target() {
        let (reply, target) = attempt(b"password").await;
        assert_eq!(reply, [1, 0]);
        assert_eq!(target.unwrap(), [1, 8, 8, 8, 8, 1, 187]);
    }
    #[tokio::test]
    async fn rejected_credentials_never_open_a_target() {
        let (reply, target) = attempt(b"incorrect").await;
        assert_eq!(reply, [1, 1]);
        assert!(target.is_err());
    }
}
