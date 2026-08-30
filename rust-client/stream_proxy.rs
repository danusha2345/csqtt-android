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
    sync::{Mutex, mpsc},
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;

pub const MAGIC: &[u8; 6] = b"CSQPX1";
const HEADER_LEN: usize = MAGIC.len() + 1 + 8;
const MAX_DATA: usize = 2_000;
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

pub async fn start(
    bind: &str,
    dispatcher: Arc<Dispatcher>,
    pool: Arc<PacketPool>,
    cancel: CancellationToken,
) -> Result<(SocketAddr, JoinHandle<()>)> {
    let requested: SocketAddr = bind.parse().context("invalid SOCKS5 bind address")?;
    if !requested.ip().is_loopback() {
        bail!("SOCKS5 listener must use a loopback address");
    }
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
                DATA if frame.payload.len() <= MAX_DATA => Inbound::Data(frame.payload.to_vec()),
                CLOSE => Inbound::Closed,
                _ => continue,
            };
            if let Some(sender) = inbound_streams.lock().await.get(&frame.stream_id).cloned() {
                let _ = sender.try_send(event);
            }
        }
    });

    let task = tokio::spawn(async move {
        let ids = AtomicU64::new(1);
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
            let id = ids.fetch_add(1, Ordering::Relaxed).max(1);
            let dispatcher = dispatcher.clone();
            let pool = pool.clone();
            let streams = streams.clone();
            let cancel = cancel.clone();
            tokio::spawn(async move {
                let _ = handle_client(socket, id, dispatcher, pool, streams, cancel).await;
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
) -> Result<()> {
    socket.set_nodelay(true)?;
    let target = read_handshake(&mut socket).await?;
    let (tx, mut rx) = mpsc::channel(64);
    streams.lock().await.insert(stream_id, tx);
    let result = async {
        dispatcher.send_proxy_frame(&pool, &encode_frame(OPEN, stream_id, &target))?;
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
        loop {
            tokio::select! {
                _ = cancel.cancelled() => break,
                read = reader.read(&mut buffer) => match read? {
                    0 => break,
                    length => dispatcher.send_proxy_frame(&pool, &encode_frame(DATA, stream_id, &buffer[..length]))?,
                },
                inbound = rx.recv() => match inbound {
                    Some(Inbound::Data(data)) => writer.write_all(&data).await?,
                    Some(Inbound::Closed | Inbound::Error(_)) | None => break,
                    Some(Inbound::Opened) => {}
                },
            }
        }
        Result::<()>::Ok(())
    }.await;
    streams.lock().await.remove(&stream_id);
    let _ = dispatcher.send_proxy_frame(&pool, &encode_frame(CLOSE, stream_id, &[]));
    result
}

async fn read_handshake(socket: &mut TcpStream) -> Result<Vec<u8>> {
    let mut greeting = [0u8; 2];
    socket.read_exact(&mut greeting).await?;
    if greeting[0] != 5 || greeting[1] == 0 {
        bail!("invalid SOCKS5 greeting");
    }
    let mut methods = vec![0u8; greeting[1] as usize];
    socket.read_exact(&mut methods).await?;
    if !methods.contains(&0) {
        socket.write_all(&[5, 0xff]).await?;
        bail!("SOCKS5 client does not support no-auth mode");
    }
    socket.write_all(&[5, 0]).await?;
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
    use crate::{
        dispatcher::{WorkerChannels, packet_channel},
        stats::Stats,
    };

    #[test]
    fn proxy_frame_round_trip_is_strict() {
        let encoded = encode_frame(DATA, 42, b"payload");
        let frame = parse_frame(&encoded).unwrap();
        assert_eq!(frame.kind, DATA);
        assert_eq!(frame.stream_id, 42);
        assert_eq!(frame.payload, b"payload");
        assert!(!is_frame(b"ordinary IP packet"));
    }

    #[tokio::test]
    async fn local_socks5_connect_and_data_use_csqtt_frames() {
        let pool = PacketPool::new(32);
        let cancel = CancellationToken::new();
        let (dispatcher, _) = Dispatcher::start(
            "127.0.0.1:0",
            None,
            pool.clone(),
            Arc::new(Stats::default()),
            cancel.clone(),
        )
        .await
        .unwrap();
        let (latency, _latency_rx) = packet_channel(8, true);
        let (priority, priority_rx) = packet_channel(16, true);
        let (bulk, _bulk_rx) = packet_channel(8, true);
        dispatcher.register(WorkerChannels {
            id: 1,
            incarnation_id: 1,
            turn_path: Arc::from("test"),
            latency,
            priority,
            bulk,
        });
        let (address, task) = start(
            "127.0.0.1:0",
            dispatcher.clone(),
            pool.clone(),
            cancel.clone(),
        )
        .await
        .unwrap();
        let mut client = TcpStream::connect(address).await.unwrap();
        client.write_all(&[5, 1, 0]).await.unwrap();
        let mut method = [0u8; 2];
        client.read_exact(&mut method).await.unwrap();
        assert_eq!(method, [5, 0]);
        client.write_all(&[5, 1, 0, 3, 11]).await.unwrap();
        client.write_all(b"example.com").await.unwrap();
        client.write_all(&443u16.to_be_bytes()).await.unwrap();

        let open_packet = priority_rx.recv(&cancel).await.unwrap();
        let open = parse_frame(open_packet.as_slice()).unwrap();
        assert_eq!(open.kind, OPEN);
        let stream_id = open.stream_id;
        assert_eq!(open.payload[0], 3);

        let (latency, _latency_rx2) = packet_channel(8, true);
        let (priority, priority_rx2) = packet_channel(16, true);
        let (bulk, _bulk_rx2) = packet_channel(8, true);
        dispatcher.register(WorkerChannels {
            id: 0,
            incarnation_id: 2,
            turn_path: Arc::from("test"),
            latency,
            priority,
            bulk,
        });

        let opened = encode_frame(OPEN_OK, stream_id, &[]);
        let mut packet = pool.acquire();
        packet.set_read_len(opened.len()).unwrap();
        packet.as_mut_slice().copy_from_slice(&opened);
        dispatcher.return_packet(packet);
        let mut reply = [0u8; 10];
        client.read_exact(&mut reply).await.unwrap();
        assert_eq!(reply[1], 0);

        client.write_all(b"hello").await.unwrap();
        let data_packet = priority_rx.recv(&cancel).await.unwrap();
        let data = parse_frame(data_packet.as_slice()).unwrap();
        assert_eq!(data.kind, DATA);
        assert_eq!(data.payload, b"hello");
        assert!(priority_rx2.try_recv().is_none());

        let response = encode_frame(DATA, stream_id, b"world");
        let mut packet = pool.acquire();
        packet.set_read_len(response.len()).unwrap();
        packet.as_mut_slice().copy_from_slice(&response);
        dispatcher.return_packet(packet);
        let mut body = [0u8; 5];
        client.read_exact(&mut body).await.unwrap();
        assert_eq!(&body, b"world");

        cancel.cancel();
        dispatcher.shutdown().await;
        let _ = task.await;
    }
}
