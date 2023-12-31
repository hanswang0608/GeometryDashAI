package GameState;
import java.awt.*;
import java.awt.event.KeyEvent;

import Main.Game;
import TileMap.Background;

//state used to select the levels
//only 1 level is available because it is VERY time consuming
public class LevelSelectState extends GameState{
	private Background bg;
	private int currentChoice;
	private String[] options = {"Training 0", "Testing 0", "Final Battle"};
	private Color titleColor;
	private Font titleFont;
	private Font font;
	
	public LevelSelectState(GameStateManager gsm) {
		this.gsm = gsm;
		try {
			bg = new Background("/Backgrounds/menu.jpg", 1);
			
			titleColor = new Color(128, 0, 0);
			titleFont = new Font("Century Gothic", Font.BOLD, 42);
			font = new Font("Arial", Font.BOLD, 20);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		init();
	}
	
	public void init() {currentChoice = 0;}
	
	public void update() {
		bg.update();
	}
	
	public void draw(Graphics2D g) {
		bg.draw(g);
		
		g.setColor(titleColor);
		g.setFont(titleFont);
		g.drawString(Game.APP_NAME, 210, 150);
		g.setFont(font);
		g.setColor(Color.BLACK);
		g.drawString(options[currentChoice], 275, 250);
	}
	
	private void select() {
		if (currentChoice == 0) {
			gsm.beginState(GameStateManager.TRAINING_LEVEL_0_STATE);
		} else if (currentChoice == 1) {
			gsm.beginState(GameStateManager.TESTING_LEVEL_0_STATE);
		} else if (currentChoice == 2) {
			gsm.beginState(GameStateManager.LEVEL1STATE);
		}
	}
	
	public void keyPressed(int k) {
		if (k == KeyEvent.VK_ENTER) {
			select();
		}
		if (k == KeyEvent.VK_LEFT) {
			currentChoice--;
			if (currentChoice < 0) {
				currentChoice = options.length - 1;
			}
		}
		if (k == KeyEvent.VK_RIGHT) {
			currentChoice++;
			if (currentChoice > options.length - 1) {
				currentChoice = 0;
			}
		}
		if (k == KeyEvent.VK_ESCAPE) {
			gsm.setState(GameStateManager.MENUSTATE);
		}
	}
	
	public void keyReleased(int k) {};
}