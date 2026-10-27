package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.CheckBox;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndError;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTextInput;

import java.io.IOException;

/** 游戏内菜单：对局域网开放 */
public class WndLanHost extends Window {

	private static final int WIDTH = 150;
	private static final int MARGIN = 3;
	private static final int BTN = 18;

	public WndLanHost() {
		super();

		Lan.loadConfig();

		float pos = MARGIN;

		RenderedTextBlock title = PixelScene.renderTextBlock( "对局域网开放", 9 );
		title.maxWidth( WIDTH - 2 * MARGIN );
		title.setPos( MARGIN, pos );
		add( title );
		pos += title.height() + MARGIN;

		RenderedTextBlock info = PixelScene.renderTextBlock( statusText(), 6 );
		info.maxWidth( WIDTH - 2 * MARGIN );
		info.setPos( MARGIN, pos );
		add( info );
		pos += info.height() + MARGIN;

		final CheckBox localTurns = new CheckBox( "局部回合制（仅视野内推进）" ) {
			@Override
			protected void onClick() {
				super.onClick();
				Lan.setLocalTurns( checked );
			}
		};
		localTurns.checked = Lan.localTurns;
		localTurns.setRect( MARGIN, pos, WIDTH - 2 * MARGIN, BTN );
		add( localTurns );
		pos += BTN + MARGIN;

		if (!Lan.isHost()) {
			final CheckBox pvp = new CheckBox( "玩家可互相攻击（PvP）" );
			pvp.checked = Lan.pvp;
			pvp.setRect( MARGIN, pos, WIDTH - 2 * MARGIN, BTN );
			add( pvp );
			pos += BTN + MARGIN;

			final RedButton portBtn = new RedButton( "端口：" + Lan.port ) {
				@Override
				protected void onClick() {
					super.onClick();
					GameScene.show( new WndTextInput( "端口", "输入监听端口（1-65535）", String.valueOf( Lan.port ), 6, false, "确定", "取消" ) {
						@Override
						public void onSelect( boolean positive, String text ) {
							if (!positive) return;
							try {
								int p = Integer.parseInt( text.trim() );
								if (p <= 0 || p > 65535) throw new NumberFormatException();
								Lan.setPort( p );
								GameScene.show( new WndLanHost() );
							} catch (NumberFormatException e) {
								Ui.error( "端口必须是 1-65535 之间的数字" );
							}
						}
					} );
				}
			};
			portBtn.setRect( MARGIN, pos, WIDTH - 2 * MARGIN, BTN );
			add( portBtn );
			pos += BTN + MARGIN;

			RedButton action = new RedButton( "开放联机" ) {
				@Override
				protected void onClick() {
					Lan.pvp = pvp.checked;
					Lan.localTurns = localTurns.checked;
					try {
						Lan.openHost();
						hide();
						GameScene.show( new WndLanHost() );
					} catch (IOException e) {
						GameScene.show( new WndError( "开放局域网失败：" + e.getMessage() ) );
					}
				}
			};
			action.setRect( MARGIN, pos, WIDTH - 2 * MARGIN, BTN );
			add( action );
			pos += BTN + MARGIN;
		}

		RedButton close = new RedButton( "关闭窗口" ) {
			@Override
			protected void onClick() {
				hide();
			}
		};
		close.setRect( MARGIN, pos, WIDTH - 2 * MARGIN, BTN );
		add( close );
		pos += BTN + MARGIN;

		resize( WIDTH, (int)Math.ceil( pos ) );
	}

	private String statusText() {
		if (Lan.isHost()) {
			int count = Lan.host() == null ? 0 : Lan.host().playerCount();
			return "已开放！\n地址：" + LanNet.localIp() + ":" + Lan.protocolPort()
					+ "\n在线玩家：" + count + "/" + Lan.maxPlayers
					+ (Lan.pvp ? "\nPvP：开启" : "\nPvP：关闭")
					+ (Lan.localTurns ? "\n回合：局部（原版视野）" : "\n回合：共享")
					+ "\n\n房客在标题界面「多人游戏 → 直接加入」输入该地址即可加入。";
		}
		return "把当前进度对局域网开放，房客可以加入同一局游戏。\n"
				+ "开放前会自动把当前存档备份到「存档备份」（文件名带 SP- 前缀）。\n"
				+ "当前层：" + Dungeon.depth + "，端口：" + Lan.port;
	}
}
