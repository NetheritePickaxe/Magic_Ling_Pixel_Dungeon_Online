package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.watabou.utils.Bundle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** 一条 TCP 连接上的收发封装。发送是同步的（LAN 带宽足够，不做独立发送线程）。 */
public final class LanConnection {

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final Object sendLock = new Object();

	private volatile boolean closed = false;

	public LanConnection( Socket socket ) throws IOException {
		this.socket = socket;
		socket.setTcpNoDelay( true );
		socket.setKeepAlive( true );
		this.in = new BufferedInputStream( socket.getInputStream(), 32 * 1024 );
		this.out = new BufferedOutputStream( socket.getOutputStream(), 32 * 1024 );
	}

	public String remoteAddress() {
		return socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
	}

	public void send( byte type, Bundle body ) {
		if (closed) return;
		synchronized (sendLock) {
			try {
				Protocol.write( out, type, body );
			} catch (IOException e) {
				close();
			}
		}
	}

	public Protocol.Frame receive() throws IOException {
		return Protocol.read( in );
	}

	public void setReadTimeout( int millis ) throws IOException {
		socket.setSoTimeout( millis );
	}

	public boolean isClosed() {
		return closed;
	}

	public void close() {
		if (closed) return;
		closed = true;
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}

	static boolean isTimeout( IOException e ) {
		return e instanceof SocketTimeoutException;
	}
}
