// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

use std::{net::Ipv4Addr, sync::LazyLock};

pub const TUN_IFACE: &str = "csqtt1";

pub struct TunnelNetwork {
    pub prefix: [u8; 3],
    pub subnet: String,
}

impl TunnelNetwork {
    pub fn parse(value: &str) -> Result<Self, &'static str> {
        let address = value
            .strip_suffix("/24")
            .ok_or("CSQTT_TUN_SUBNET must be a private .0/24 network")?;
        let ip: Ipv4Addr = address
            .parse()
            .map_err(|_| "invalid CSQTT_TUN_SUBNET IPv4 address")?;
        let [a, b, c, d] = ip.octets();
        if !ip.is_private() || d != 0 {
            return Err("CSQTT_TUN_SUBNET must be a private .0/24 network");
        }
        Ok(Self {
            prefix: [a, b, c],
            subnet: format!("{ip}/24"),
        })
    }

    pub fn address(&self, host: u8) -> String {
        let [a, b, c] = self.prefix;
        Ipv4Addr::new(a, b, c, host).to_string()
    }
}

pub static NETWORK: LazyLock<TunnelNetwork> = LazyLock::new(|| {
    let value = std::env::var("CSQTT_TUN_SUBNET").unwrap_or_else(|_| "10.66.67.0/24".into());
    TunnelNetwork::parse(&value).unwrap_or_else(|error| {
        eprintln!("[CONFIG] {error}");
        std::process::exit(2);
    })
});

pub fn tunnel_subnet() -> &'static str {
    &NETWORK.subnet
}
pub fn tunnel_address(host: u8) -> String {
    NETWORK.address(host)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn custom_network_addresses_and_validation() {
        let network = TunnelNetwork::parse("10.77.88.0/24").unwrap();
        assert_eq!(network.address(1), "10.77.88.1");
        assert_eq!(network.address(2), "10.77.88.2");
        assert_eq!(network.address(250), "10.77.88.250");
        for value in [
            "8.8.8.0/24",
            "10.1.2.1/24",
            "10.1.0.0/16",
            "10.999.1.0/24",
            "10.1.2.0/24;id",
        ] {
            assert!(TunnelNetwork::parse(value).is_err(), "{value}");
        }
    }
}
