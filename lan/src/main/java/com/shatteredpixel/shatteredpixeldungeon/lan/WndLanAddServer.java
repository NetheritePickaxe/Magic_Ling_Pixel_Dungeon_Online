package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;

/** 添加服务器：只保存地址到服务器列表，不立即连接。 */
public class WndLanAddServer extends WndTextInput {

	public WndLanAddServer() {
		super( "添加服务器",
				"输入要保存的房主地址，格式为 IP 或 IP:端口（默认端口 " + Protocol.DEFAULT_PORT + "）",
				"",
				40,
				false,
				"保存",
				"取消" );
	}

	@Override
	public void onSelect( boolean positive, String text ) {
		if (!positive) return;

		String address = WndLanJoin.normalize( text );
		if (address == null) {
			Ui.error( "地址格式不正确，例如 192.168.1.10 或 192.168.1.10:" + Protocol.DEFAULT_PORT );
			return;
		}

		LanHosts.remember( address );
		ShatteredPixelDungeon.switchNoFade( LanServersScene.class );
	}
}
