package GameState;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;

import Audio.AudioPlayer;
import Entity.*;
import Main.GamePanel;
import TileMap.Background;
import TileMap.TileMap;

import JavaNN.Training.*;
import JavaNN.Util.Config;

public class TrainingMode extends Mode{
	private ArrayList<PlayerManager> players;
	private double deathTime; 	//keeps track of time of death, to create a 1 second respawn delay
	private boolean running;	//determines if the player should be updated
	
	private Population population;
	private int numAlive;
	private int generation;
	private int trainingSpeed;

	private static final int BASE_RESPAWN_DELAY = 250;
	private static final double SPAWN_X = 320; 
	private static final double SPAWN_Y = 560; 

	private static final int AI_VIEW_DISTANCE = 5;
	private static final int POPULATION_SIZE = 50;
	private static final int[] NETWORK_ARCHITECTURE = {AI_VIEW_DISTANCE + 1, 6, 4, 1};
	private static final int[] TRAINING_TICK_RATES = {60, 120, 240, 600, 2400, 6000};

	private static final float TRAILING_OPACITY = 0.15f;

    public TrainingMode(GameStateManager gsm, Background bg, TileMap tileMap, AudioPlayer music) {
        this.gsm = gsm;
		this.bg = bg;
		this.tileMap = tileMap;
		this.music = null;

		players = new ArrayList<PlayerManager>();

        orbs = new ArrayList<Orb>();
		pads = new ArrayList<Pad>();
		gportals = new ArrayList<GravityPortal>();
		portals = new ArrayList<Portal>();
		explosions = new ArrayList<Explosion>();

		numAlive = POPULATION_SIZE;
		generation = 0;
		trainingSpeed = 0;
    }

    public void init() {
        // initialize tilemap
        tileMap.loadTiles();
		tileMap.loadMap();
		tileMap.setPosition(0, 0);
		tileMap.setTween(1);

		// clear old entities
		orbs.clear();
		pads.clear();
		gportals.clear();
		portals.clear();
		explosions.clear();

        // create entities by scanning the level's tilemap
		scanMap(tileMap.getMap());

		population = new Population(POPULATION_SIZE, NETWORK_ARCHITECTURE);

        //initialize player settings
		players.clear();
		for (int i = 0; i < POPULATION_SIZE; i++) {
			players.add(new PlayerManager(tileMap));
		}
		deathTime = -1;
		setPlayers();
		running = true;
		numAlive = POPULATION_SIZE;
		generation = 0;
		trainingSpeed = 0;
		GamePanel.numTicks = TRAINING_TICK_RATES[trainingSpeed];

		Config.saveMostFitPerGen = false;
    }

    public void update() {
		if (numAlive == 0 && running) {
			deathTime = System.nanoTime();
			running = false;
			stopMusic();
		}

		//if it has been 1 second since dying, respawn the player
		int respawnDelay = Math.floorDiv(BASE_RESPAWN_DELAY, GamePanel.numTicks/60);
		if (deathTime != -1 && (System.nanoTime() - deathTime) / 1000000 > respawnDelay) {
			population.selectParentsByRank(2);
			population.crossoverPopulation();
			population.mutatePopulation();
			population.updatePopulation();
			reset();
			generation++;
			if (Config.saveMostFitPerGen) {
				population.getMostFit().getNetwork().saveToFile("ai_models/temp/training-gen-"+generation+".model", true);
			}
		}

		int leadingPlayer = getLeadingPlayer();
		
		for (int i = 0; i < POPULATION_SIZE; i++) {
			PlayerManager pm = players.get(i);
			Player player = pm.getPlayer();
			Agent agent = population.getAgents()[i];

			if (player.isDead()) continue;

			//update player
			if (running) pm.update();

			// win condition
			if(player.atEndOfLevel()) {
				player.setMoving(false);
				if (player.getDX() == 0) {
					running = false;
					gsm.setState(GameStateManager.WINSTATE);
					agent.setFitness(player.getx());
					if (Config.saveWinner) {
						agent.getNetwork().saveToFile("ai_models/training-win.model", true);
					}
					System.out.println("Finished training on generation " + generation + ".");
					GamePanel.numTicks = TRAINING_TICK_RATES[0];
				}
			}
			
			// death update
			if(player.isDead()) {
				Explosion explosion = new Explosion(player.getx(), player.gety());
				if (i != leadingPlayer) {
					explosion.setOpacity(TRAILING_OPACITY);
				}
				explosions.add(explosion);
				numAlive--;
				agent.setFitness(player.getx());
			}

			// get jump input from neural network
			double[] networkInputs = getNetworkInputs(pm, true);
			double networkOutput = agent.act(networkInputs)[0];
			boolean shouldJump = networkOutput >= 0.98;
			if (shouldJump) {
				startJumping(pm);
			} else {
				stopJumping(pm);
			}
	
			//update background
			bg.setPosition(tileMap.getx(), tileMap.gety());
	
			//update entities
			for (int j = 0; j < orbs.size(); j++) {
				if (player.intersects(orbs.get(j)) && player.getJumping() && player.isFirstJump() && !orbs.get(j).getActivatedOnce()) {
					player.hitOrb(orbs.get(j));
				}
				orbs.get(j).update();
			}
			
			for (int j = 0; j < pads.size(); j++) {
				if (player.intersects(pads.get(j)) && !pads.get(j).getActivatedOnce()) {
					player.hitPad(pads.get(j));
				}
			}
			
			for (int j = 0; j < gportals.size(); j++) {
				if (player.intersects(gportals.get(j))) {
					if (gportals.get(j).getType() == GravityPortal.NORMAL || gportals.get(j).getType() == GravityPortal.NORMALH) {
						if (player.getGravity() != 1) player.flipGravity();
					}
					else {
						if (player.getGravity() != -1) player.flipGravity();
					}
				}
			}
			
			for (int j = 0; j < portals.size(); j++) {
				if (player.intersects(portals.get(j))) {
					if(portals.get(j).getType() == Portal.CUBE) pm.setPlayer(Portal.CUBE);
					else if(portals.get(j).getType() == Portal.SHIP) pm.setPlayer(Portal.SHIP);
					else if(portals.get(j).getType() == Portal.BALL) pm.setPlayer(Portal.BALL);
					else if(portals.get(j).getType() == Portal.WAVE) pm.setPlayer(Portal.WAVE);
				}
			}
		}

		//locks the vertical movement of the screen for modes other than Cube
		// lock camera movement to the player furthest ahead
		Player player = players.get(getLeadingPlayer()).getPlayer();
		if (player instanceof Cube) {
			tileMap.setPosition(GamePanel.WIDTH / 2 - player.getx(), GamePanel.HEIGHT / 2 - player.gety()); 
		}
		else {
			tileMap.setPosition(GamePanel.WIDTH / 2 - player.getx());
		}
		
		//update explosion
		for (int j = 0; j < explosions.size(); j++) {
			explosions.get(j).update();
			if (explosions.get(j).shouldRemove()) explosions.remove(j);
		}
	}

    public void draw(Graphics2D g) {
		//draw background
		bg.draw(g);
		
		//draw map
		tileMap.draw(g);
		
		//draw entities and player
		for(int i = 0; i < orbs.size(); i++) {
			orbs.get(i).draw(g);
		}
		
		for (int i = 0; i < pads.size(); i++) {
			pads.get(i).draw(g);
		}
		
		if (running) {
			int leadingPlayer = getLeadingPlayer();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, TRAILING_OPACITY));
			// draw the trailing players in reverse order, with translucency
			for (int i = players.size()-1; i >= 0; i--) {
				if (!players.get(i).getPlayer().isDead() && i != leadingPlayer) {
					players.get(i).draw(g);	
				}
			}
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
			// draw the leading player last so it's on top and opaque
			players.get(leadingPlayer).draw(g);
		}
		
		for (int i = 0; i < gportals.size(); i++) {
			gportals.get(i).draw(g);
		}
		
		for (int i = 0; i < portals.size(); i++) {
			portals.get(i).draw(g);
		}
		
		for (int i = 0; i < explosions.size(); i++) {
			explosions.get(i).setMapPosition((int)tileMap.getx(), (int)tileMap.gety());
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, explosions.get(i).getOpacity()));
			explosions.get(i).draw(g);
		}
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));


		g.setColor(new Color(1, 1, 1, 0.5f));
		g.setFont(new Font("Calibri", Font.BOLD, 20));
		g.drawString("Gen " + generation, GamePanel.WIDTH/2-20, 20);

		// String speed = new String(new char[trainingSpeed]).replace("\0", ">");
		if (trainingSpeed != 0) {
			String speed = TRAINING_TICK_RATES[trainingSpeed]/TRAINING_TICK_RATES[0] + "x";
			g.drawString("speed: " + speed, GamePanel.WIDTH-100, 20);
		}

		//Note: draw is set up to draw objects in order of appearance
	}

    //key listeners
	public void keyPressed(int k) {
		if (k == KeyEvent.VK_UP) {
			// for (PlayerManager pm : players) {
			// 	startJumping(pm);
			// }
		}
		if (k == KeyEvent.VK_ESCAPE){
			gsm.beginState(GameStateManager.PAUSESTATE);		//esc to pause
			GamePanel.numTicks = TRAINING_TICK_RATES[0];
		} 
		if (k == KeyEvent.VK_R) {reset();} 		//r to restart level
	}

	public void keyReleased(int k) {
		if (k == KeyEvent.VK_UP) {
			// disable this because it's constantly being invoked due to the way gsm.keyUpdate() is setup, stoping the AI's jumps
			// for (PlayerManager pm : players) {
			// 	stopJumping(pm);
			// }
		}
		if (k == KeyEvent.VK_COMMA) {
			trainingSpeed--;
			if (trainingSpeed < 0) trainingSpeed = 0;
			GamePanel.numTicks = TRAINING_TICK_RATES[trainingSpeed];
			System.out.println("tick rate target: " + GamePanel.numTicks);
		}
		if (k == KeyEvent.VK_PERIOD) {
			trainingSpeed++;
			if (trainingSpeed > TRAINING_TICK_RATES.length-1) trainingSpeed = TRAINING_TICK_RATES.length-1;
			GamePanel.numTicks = TRAINING_TICK_RATES[trainingSpeed];
			System.out.println("tick rate target: " + GamePanel.numTicks);
		}
	}

	private void startJumping(PlayerManager pm) {
		pm.getPlayer().setJumping(true);
		//calculating the firstJump condition
		if (pm.getPlayer().isFirstClick()) {
			pm.getPlayer().setFirstClickTime(System.nanoTime());
			pm.getPlayer().setFirstClick(false);
		}
		if ((System.nanoTime() - pm.getPlayer().getFirstClickTime()) / 1000000 < 100) {
			pm.getPlayer().setFirstJump(true);
		}
		else pm.getPlayer().setFirstJump(false);
	}

	private void stopJumping(PlayerManager pm) {
		pm.getPlayer().setJumping(false);
		pm.getPlayer().setFirstClick(true);
	}

    //method to reset player, music, and some entites in order to restart the level
	protected void reset() {
		numAlive = POPULATION_SIZE;
		deathTime = -1;
		setPlayers();
		running = true;
		playMusic();
		for (int i = 0; i < orbs.size(); i++) {
			orbs.get(i).setActivated(false);
		}
		for (int i = 0; i < pads.size(); i++) {
			pads.get(i).setActivated(false);
		}
	}

    //creating and spawning the player
	protected void setPlayers() {
		int playerInd = 0;
		double spawnX = SPAWN_X;
		while (playerInd < players.size()) {
			if (spawnX < 10) spawnX = SPAWN_X;
			PlayerManager pm = players.get(playerInd);
			pm.getPlayer().setDead(false);
			pm.init();
			pm.getPlayer().initValues();
			pm.getPlayer().setPosition(spawnX, SPAWN_Y);
			playerInd++;
			spawnX -= pm.getPlayer().getDX();
		}
	}

	private int getLeadingPlayer() {
		int furthest = 0;
		for (int i = 1; i < players.size(); i++) {
			if (players.get(i).getPlayer().getx() >= players.get(furthest).getPlayer().getx()) {
				furthest = i;
			}
		}
		return furthest;
	}

	private double[] getNetworkInputs(PlayerManager pm, boolean shouldNormalize) {
		int tileSize = tileMap.getTileSize();
		int playerFront = pm.getPlayer().getx() + pm.getPlayer().getCWidth()/2;
		int nextColX = Math.ceilDiv(playerFront, tileSize) * tileSize;

		double[] output = new double[AI_VIEW_DISTANCE + 1]; // network can see 10 blocks in front
		output[0] = nextColX - playerFront;
		
		byte[][] map = tileMap.getMap();
		byte[] row = map[(int)SPAWN_Y/32];
		int col = (int)Math.ceil((double)playerFront / tileSize);
		for (int i = 0; i+col < row.length && i < AI_VIEW_DISTANCE; i++) {
			output[i+1] = (double)row[i+col];
		}

		if (shouldNormalize) {
			output[0] /= tileSize;
			for (int i = 1; i < output.length; i++) {
				if (output[i] > 0) output[i] = 1;
			}
		}

		return output;
	}
}
