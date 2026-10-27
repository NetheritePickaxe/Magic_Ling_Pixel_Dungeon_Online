package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndMessage;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;

/** 直接加入：输入房主地址并连接，不自动保存到服务器列表。 */
public class WndLanJoin extends WndTextInput {

	public WndLanJoin() {
		super( "直接加入",
				"输入房主的地址，格式为 IP 或 IP:端口（默认端口 " + Protocol.DEFAULT_PORT + "）",
				LanHosts.last(),
				40,
				false,
				"加入",
				"取消" );
	}

	@Override
	public void onSelect( boolean positive, String text ) {
		if (!positive) return;
		connect( text );
	}

	static String normalize( String text ) {
		String[] addr = LanNet.parse( text == null ? "" : text.trim() );
		if (addr == null) return null;

		int port;
		try {
			port = Integer.parseInt( addr[1] );
		} catch (NumberFormatException e) {
			port = Protocol.DEFAULT_PORT;
		}
		if (port <= 0 || port > 65535) return null;
		return addr[0] + (port != Protocol.DEFAULT_PORT ? ":" + port : "");
	}

	static void connect( String text ) {
		String normalized = normalize( text );
		if (normalized == null) {
			Ui.error( "地址格式不正确，例如 192.168.1.10 或 192.168.1.10:" + Protocol.DEFAULT_PORT );
			return;
		}

		String[] addr = LanNet.parse( normalized );
		int port = Integer.parseInt( addr[1] );

		HeroClass heroClass;
		try {
			heroClass = HeroClass.values()[SPDSettings.lastClass()];
		} catch (Exception e) {
			heroClass = HeroClass.WARRIOR;
		}

		ClientSession session = new ClientSession( addr[0], port, SPDSettings.heroName(), heroClass );
		Lan.enterClient( session );
		session.connect();

		Ui.postShow( new WndMessage( "正在连接房主…" ) );
	}
}
