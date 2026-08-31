include $(TOPDIR)/rules.mk

PKG_NAME:=csqtt-client
PKG_VERSION:=2.1.9
PKG_RELEASE:=1

include $(INCLUDE_DIR)/package.mk

define Package/csqtt-client
  SECTION:=net
  CATEGORY:=Network
  TITLE:=CSQTT client for OpenWrt routers
  URL:=https://github.com/danusha2345/csqtt-android
  DEPENDS:=+libc +kmod-tun +ip-full +iptables-nft
endef

define Package/csqtt-client/description
 Native CSQTT TUN and SOCKS5 client with a procd service for OpenWrt.
endef

define Package/csqtt-client/conffiles
/etc/config/csqtt
endef

define Build/Prepare
	$(call Build/Prepare/Default)
	test -n "$(CSQTT_BINARY)"
	test -x "$(CSQTT_BINARY)"
	$(CP) "$(CSQTT_BINARY)" $(PKG_BUILD_DIR)/csqtt-client
endef

define Build/Compile
endef

define Package/csqtt-client/install
	$(INSTALL_DIR) $(1)/usr/bin $(1)/usr/libexec $(1)/etc/init.d $(1)/etc/config
	$(INSTALL_BIN) $(PKG_BUILD_DIR)/csqtt-client $(1)/usr/bin/csqtt-client
	$(INSTALL_BIN) ./files/usr/libexec/csqtt-tun $(1)/usr/libexec/csqtt-tun
	$(INSTALL_BIN) ./files/etc/init.d/csqtt $(1)/etc/init.d/csqtt
	$(INSTALL_CONF) ./files/etc/config/csqtt $(1)/etc/config/csqtt
endef

$(eval $(call BuildPackage,csqtt-client))
