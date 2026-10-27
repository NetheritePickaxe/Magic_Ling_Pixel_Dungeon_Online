package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.custom.utils.NetIcons;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Archs;
import com.shatteredpixel.shatteredpixeldungeon.ui.ExitButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.watabou.noosa.Camera;

/** 多人游戏主页：联机存档与服务器列表各为独立页面。 */
public class LanScene extends PixelScene {

	@Override
	public void create() {
		super.create();

		int w = Camera.main.width;
		int h = Camera.main.height;

		Archs archs = new Archs();
		archs.setSize( w, h );
		add( archs );

		ExitButton btnExit = new ExitButton();
		btnExit.setPos( w - btnExit.width(), 0 );
		add( btnExit );

		IconTitle title = new IconTitle( NetIcons.get( NetIcons.PLAYERS ), "多人游戏" );
		title.setSize( 200, 0 );
		title.setPos( (w - title.reqWidth()) / 2f, (20 - title.height()) / 2f );
		align( title );
		add( title );

		final int BTN = 20;
		float width = Math.min( 160, w - 40 );
		float x = (w - width) / 2f;
		float y = (h - BTN) / 2f - 12;

		RedButton servers = new RedButton( "服务器列表" ) {
			@Override
			protected void onClick() {
				super.onClick();
				ShatteredPixelDungeon.switchNoFade( LanServersScene.class );
			}
		};
		servers.icon( Icons.get( Icons.CHANGES ) );
		servers.setRect( x, y, width, BTN );
		add( servers );

		RenderedTextBlock hint = PixelScene.renderTextBlock(
				"想开房？正常进入一局游戏，\n在游戏内菜单点「对局域网开放」。\n开放前会自动把当前存档备份到「存档备份」。", 6 );
		hint.maxWidth( (int) width );
		hint.setPos( x + (width - hint.width()) / 2f, servers.bottom() + 10 );
		align( hint );
		add( hint );

		fadeIn();
	}

	@Override
	protected void onBackPressed() {
		ShatteredPixelDungeon.switchNoFade( TitleScene.class );
	}
}
