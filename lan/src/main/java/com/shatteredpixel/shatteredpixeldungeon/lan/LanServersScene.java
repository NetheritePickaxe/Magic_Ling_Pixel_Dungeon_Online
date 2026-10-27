package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Archs;
import com.shatteredpixel.shatteredpixeldungeon.ui.IconButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.watabou.noosa.Camera;

import java.util.List;

/** 服务器列表：已保存服务器独立成页，底部提供直接加入与添加服务器。 */
public class LanServersScene extends PixelScene {

	private static final int BTN = 20;

	@Override
	public void create() {
		super.create();

		int w = Camera.main.width;
		int h = Camera.main.height;

		Archs archs = new Archs();
		archs.setSize( w, h );
		add( archs );

		IconButton back = new IconButton( Icons.get( Icons.EXIT ) ) {
			@Override
			protected void onClick() {
				ShatteredPixelDungeon.switchNoFade( LanScene.class );
			}
		};
		back.setRect( w - 20, 0, 20, 20 );
		add( back );

		IconTitle title = new IconTitle( Icons.get( Icons.CHANGES ), "服务器列表" );
		title.setSize( 200, 0 );
		title.setPos( (w - title.reqWidth()) / 2f, (20 - title.height()) / 2f );
		align( title );
		add( title );

		float width = Math.min( 160, w - 40 );
		float x = (w - width) / 2f;
		float pos = title.bottom() + 12;

		List<String> servers = LanHosts.recent();
		if (servers.isEmpty()) {
			RenderedTextBlock empty = PixelScene.renderTextBlock( "还没有保存的服务器\n点击下方「添加服务器」保存地址", 6 );
			empty.maxWidth( (int) width );
			empty.setPos( x + (width - empty.width()) / 2f, pos + 8 );
			align( empty );
			add( empty );
		} else {
			for (final String host : servers) {
				RedButton server = new RedButton( host ) {
					@Override
					protected void onClick() {
						super.onClick();
						WndLanJoin.connect( host );
					}
				};
				server.setRect( x, pos, width, BTN );
				add( server );
				pos += BTN + 2;
			}
		}

		float bottom = Math.max( pos + 8, h - BTN - 14 );
		float half = (width - 4) / 2f;

		RedButton directJoin = new RedButton( "直接加入" ) {
			@Override
			protected void onClick() {
				super.onClick();
				Ui.show( new WndLanJoin() );
			}
		};
		directJoin.setRect( x, bottom, half, BTN );
		add( directJoin );

		RedButton addServer = new RedButton( "添加服务器" ) {
			@Override
			protected void onClick() {
				super.onClick();
				Ui.show( new WndLanAddServer() );
			}
		};
		addServer.setRect( directJoin.right() + 4, bottom, half, BTN );
		add( addServer );

		fadeIn();
	}

	@Override
	protected void onBackPressed() {
		ShatteredPixelDungeon.switchNoFade( LanScene.class );
	}
}
