package com.shatteredpixel.shatteredpixeldungeon.lan;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/** 局域网地址工具：本机 IP 展示 + "IP[:端口]" 解析。 */
final class LanNet {

	private LanNet() {
	}

	static String localIp() {
		try {
			Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
			for (NetworkInterface ni : Collections.list( ifaces )) {
				if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
				for (InetAddress addr : Collections.list( ni.getInetAddresses() )) {
					if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
						return addr.getHostAddress();
					}
				}
			}
			for (NetworkInterface ni : Collections.list( ifaces )) {
				if (!ni.isUp() || ni.isLoopback()) continue;
				for (InetAddress addr : Collections.list( ni.getInetAddresses() )) {
					if (addr instanceof Inet4Address) return addr.getHostAddress();
				}
			}
		} catch (Exception ignored) {
		}
		return "127.0.0.1";
	}

	/** 解析 "ip" 或 "ip:port"，返回 {ip, port}，失败返回 null */
	static String[] parse( String raw ) {
		if (raw == null) return null;
		String text = raw.trim();
		if (text.isEmpty()) return null;

		String host = text;
		int port = Protocol.DEFAULT_PORT;

		int idx = text.lastIndexOf( ':' );
		if (idx > 0 && idx < text.length() - 1) {
			try {
				port = Integer.parseInt( text.substring( idx + 1 ).trim() );
				host = text.substring( 0, idx ).trim();
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (host.isEmpty() || port <= 0 || port > 65535) return null;
		return new String[]{host, String.valueOf( port )};
	}
}
