package bguspl.set.ex;

import java.util.logging.Level;

import bguspl.set.Env;
import java.lang.Math;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;
    /**
     * The Queue of the player's key presses.
     */
    private BlockingQueue<Integer> myKeyPresses;

    /**
     * The max key presses of a player.
     */
    private final int capacity;

    private final Dealer dealer;

    protected LinkedList<Integer> currSet; // list of tokens

    //private boolean backFromPenalty;

    private long freezeTimer;

    private volatile boolean isSleeping;

    private volatile boolean checked;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.capacity = env.config.featureSize; // 3
        this.myKeyPresses = new ArrayBlockingQueue<>(this.capacity);
        freezeTimer = 0;
        currSet = new LinkedList<Integer>();
        isSleeping = false;
        checked = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
            // if should sleep because penalty or point
            if (isSleeping && freezeTimer > 0) { // player in freeze time
                for (long i = freezeTimer/1000; i > 0; i--){
                    env.ui.setFreeze(id, i*1000);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {}
                }
                env.ui.setFreeze(id, 0);
                freezeTimer = 0;
            }
            // back to work
            checked = false;
            isSleeping = false;
            int slot;
            synchronized (myKeyPresses) {
            while (myKeyPresses.isEmpty() && !terminate) { // sleep until there is a new press
                    try {
                        myKeyPresses.wait();
                    } catch (InterruptedException e) {}
                }
                slot = myKeyPresses.remove();
                myKeyPresses.notifyAll();
            }
            consume(slot);
        }
    
        if (!human){
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {}
        }
    }


    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)

        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                // TODO implement player key press simulator
                synchronized (myKeyPresses) {
                    while (myKeyPresses.size() == capacity) {
                        try {
                            myKeyPresses.wait();
                        } catch (InterruptedException e) {}
                    }
                    Player.this.keyPressed(randomSlot());
                }
            }

            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        System.out.println("ai Thread: " + aiThread.getName());
        aiThread.start();

    }

    // Consumes from myKeyPresses and putting into currSet accordingly
    private void consume(int slot) {

        // synch!!!!!!!!!!!!!!!!
        if (table.slotToCard[slot] != null) {
            if (table.tokens[slot].contains(this)) // remove token
            {
                table.removeToken(this, slot);
            }
            else { // place token
                if (currSet.size() == capacity) return; // cant put more tokens
                table.placeToken(this, slot);
                if (currSet.size() == capacity) { // if it is the third token
                    isSleeping = true; // blocking ai when dealer check set
                    dealer.addPlayerToQ(this); // Checking set
                    //dealer.counter[id]++;
                    try {
                        synchronized (this) {
                            while (!checked)
                                wait();
                        }
                    } catch (InterruptedException e) {}
                }
            }
        }
    }

    private int randomSlot() {
        int slot = (int) (Math.random() * (double) (env.config.playerKeys(this.id).length));
        return slot;
    }

    //public void newCurrSet() {
    //    currSet = new LinkedList<Integer>();
    //}

    public void setCurrSet(int slot) { // remove token from currSet
        synchronized(currSet){              
            currSet.remove((Integer) slot);
        }
    }
    

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        // TODO implement
        if(!human)
        {
            aiThread.interrupt();
        }
        terminate = true;
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // TODO implement
        if (!isSleeping && !dealer.lockGame) {
            synchronized(myKeyPresses) {
                myKeyPresses.add(slot);
                myKeyPresses.notifyAll();
            }
        }
    }

    public LinkedList<Integer> getSet() {
        return currSet;
    }

    public synchronized void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        score = score + 1;
        env.ui.setScore(id, score);
        //newCurrSet(); 
        freezeTimer = env.config.pointFreezeMillis;
        checked = true;
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName());

    }

    public synchronized void cancel() {
        // TODO implement
        checked = true;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public synchronized void penalty() {
        // TODO implement
        freezeTimer = env.config.penaltyFreezeMillis;
        checked = true;
    }

    public int score() {
        return score;
    }

    public boolean getChecked()
    {
        return checked;
    }

}