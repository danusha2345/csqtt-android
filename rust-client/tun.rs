// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

#[cfg(target_os = "linux")]
use anyhow::Context;
use anyhow::{Result, bail};
use std::fs::File;
#[cfg(target_os = "linux")]
use std::fs::OpenOptions;
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub enum Source {
    Uds(String),
    Device(String),
}

impl Source {
    pub async fn open(&self, cancel: CancellationToken) -> Result<File> {
        match self {
            Self::Uds(name) => receive_fd(name.clone(), cancel).await,
            Self::Device(name) => open_device(name),
        }
    }

    pub fn description(&self) -> String {
        match self {
            Self::Uds(name) => format!("UDS {name}"),
            Self::Device(name) => format!("TUN {name}"),
        }
    }
}

#[cfg(any(target_os = "linux", test))]
fn validate_device_name(name: &str) -> Result<()> {
    if name.is_empty() || name.len() >= libc::IFNAMSIZ {
        bail!(
            "имя TUN должно содержать от 1 до {} байт",
            libc::IFNAMSIZ - 1
        );
    }
    if !name
        .bytes()
        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
    {
        bail!("имя TUN содержит недопустимые символы");
    }
    Ok(())
}

#[cfg(target_os = "linux")]
pub fn open_device(name: &str) -> Result<File> {
    use std::os::fd::AsRawFd;

    validate_device_name(name)?;
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/net/tun")
        .context("открытие /dev/net/tun")?;
    let mut request: libc::ifreq = unsafe { std::mem::zeroed() };
    for (target, byte) in request.ifr_name.iter_mut().zip(name.bytes()) {
        *target = byte as libc::c_char;
    }
    request.ifr_ifru.ifru_flags = (libc::IFF_TUN | libc::IFF_NO_PI) as libc::c_short;
    let result = unsafe { libc::ioctl(file.as_raw_fd(), libc::TUNSETIFF, &request) };
    if result < 0 {
        return Err(std::io::Error::last_os_error()).context("создание Linux TUN интерфейса");
    }
    let status = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFL) };
    if status == -1
        || unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETFL, status | libc::O_NONBLOCK) } == -1
        || unsafe { libc::fcntl(file.as_raw_fd(), libc::F_SETFD, libc::FD_CLOEXEC) } == -1
    {
        return Err(std::io::Error::last_os_error()).context("настройка Linux TUN descriptor");
    }
    Ok(file)
}

#[cfg(not(target_os = "linux"))]
pub fn open_device(_name: &str) -> Result<File> {
    bail!("--tun-device поддерживается только на Linux/OpenWrt")
}

#[cfg(unix)]
pub async fn receive_fd(name: String, cancel: CancellationToken) -> Result<File> {
    use nix::{
        cmsg_space,
        errno::Errno,
        sys::socket::{
            AddressFamily, Backlog, ControlMessageOwned, MsgFlags, SockFlag, SockType, UnixAddr,
            accept, bind, listen, recvmsg, socket,
        },
    };
    use std::{
        io::IoSliceMut,
        os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd},
    };
    use tokio::io::unix::AsyncFd;

    fn configure_nonblocking(descriptor: RawFd) -> Result<()> {
        let status = unsafe { libc::fcntl(descriptor, libc::F_GETFL) };
        if status == -1
            || unsafe { libc::fcntl(descriptor, libc::F_SETFL, status | libc::O_NONBLOCK) } == -1
            || unsafe { libc::fcntl(descriptor, libc::F_SETFD, libc::FD_CLOEXEC) } == -1
        {
            return Err(std::io::Error::last_os_error().into());
        }
        Ok(())
    }

    let listener = socket(
        AddressFamily::Unix,
        SockType::Stream,
        SockFlag::SOCK_CLOEXEC | SockFlag::SOCK_NONBLOCK,
        None,
    )?;
    bind(
        listener.as_raw_fd(),
        &UnixAddr::new_abstract(name.as_bytes())?,
    )?;
    listen(&listener, Backlog::new(1)?)?;
    let listener = AsyncFd::new(listener)?;
    let connection = loop {
        tokio::select! {
            _ = cancel.cancelled() => bail!("TUN FD wait cancelled"),
            ready = listener.readable() => {
                let mut ready = ready?;
                match accept(listener.get_ref().as_raw_fd()) {
                    Ok(descriptor) => {
                        let descriptor = unsafe { OwnedFd::from_raw_fd(descriptor) };
                        configure_nonblocking(descriptor.as_raw_fd())?;
                        break descriptor;
                    }
                    Err(Errno::EAGAIN) => ready.clear_ready(),
                    Err(error) => return Err(error.into()),
                }
            }
        }
    };
    let connection = AsyncFd::new(connection)?;
    let mut data = [0u8; 1];
    loop {
        tokio::select! {
            _ = cancel.cancelled() => bail!("TUN FD receive cancelled"),
            ready = connection.readable() => {
                let mut ready = ready?;
                let mut slices = [IoSliceMut::new(&mut data)];
                let mut ancillary = cmsg_space!([std::os::fd::RawFd; 1]);
                match recvmsg::<UnixAddr>(
                    connection.get_ref().as_raw_fd(),
                    &mut slices,
                    Some(&mut ancillary),
                    MsgFlags::empty(),
                ) {
                    Ok(message) => {
                        if message.bytes == 0 {
                            bail!("TUN FD connection closed");
                        }
                        for control in message.cmsgs()? {
                            if let ControlMessageOwned::ScmRights(descriptors) = control
                                && let Some(descriptor) = descriptors.into_iter().next()
                            {
                                let file = unsafe { File::from_raw_fd(descriptor) };
                                configure_nonblocking(file.as_raw_fd())?;
                                return Ok(file);
                            }
                        }
                        bail!("no fd received");
                    }
                    Err(Errno::EAGAIN) => ready.clear_ready(),
                    Err(error) => return Err(error.into()),
                }
            }
        }
    }
}

#[cfg(not(unix))]
pub async fn receive_fd(_name: String, _cancel: CancellationToken) -> Result<File> {
    bail!("TUN FD transport is available only on Android and Unix")
}

#[cfg(test)]
mod tests {
    use super::validate_device_name;

    #[test]
    fn accepts_openwrt_tun_names() {
        assert!(validate_device_name("csqtt0").is_ok());
        assert!(validate_device_name("vpn-tun.1").is_ok());
    }

    #[test]
    fn rejects_unsafe_or_truncated_tun_names() {
        assert!(validate_device_name("").is_err());
        assert!(validate_device_name("bad/name").is_err());
        assert!(validate_device_name("0123456789abcdef").is_err());
    }
}
