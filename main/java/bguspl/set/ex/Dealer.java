package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Queue;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.lang.Math;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private List<Integer> deck;

    /**
     * queue of players to check
     */
    private Queue<Player> qPlayerToCheck;
    /**
     * 
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime;

    /**
     * The thread representing the dealer.
     */
    private Thread dealerThread;

    /**
     * contains all threads of the players in the game
     */
    private Thread[] threadsArray;

    protected volatile boolean lockGame;

    //protected int[] counter;

// private Boolean nonHuman;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.qPlayerToCheck = new LinkedList<Player>();
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        reshuffleTime = env.config.turnTimeoutMillis;
        // initialize players threads
        threadsArray = new Thread[players.length];
        lockGame = true;
        //Starvation Check
        //counter = new int[players.length];
        // for(int i = 0; i < counter.length; i++)
        // {
        //     counter[i] = 0;
        // }
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");

        for (int i = 0; i < threadsArray.length; i++) {
            threadsArray[i] = new Thread(players[i]);
            threadsArray[i].start();
        }
        //lockAll();
        while (!shouldFinish()) {
            placeAllCardsOnTable();
            updateTimerDisplay(true);
            unlockAll();
            timerLoop();
            lockAll();
            removeAllCardsFromTable();
        }
        //Starvation check
        // for(int i =0; i < players.length; i++){
        //     System.out.print(counter[i]+ "    ");
        // }
        announceWinners();
        terminate();
    }

    private void lockAll() {
        lockGame = true;
    }

    private void unlockAll() {
        lockGame = false;
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(false);
            sleepUntilWokenOrTimeout();
            while (!qPlayerToCheck.isEmpty()) {
                dealerCheck();
            }
        }
    }

    private void dealerCheck() {
        Player p;
        synchronized (qPlayerToCheck) {
            p = qPlayerToCheck.remove();
        }
        if (p.getSet().size() == env.config.featureSize) { // set in legal size
            //System.out.println("before checkSet: " + p.id + " currSet in dealerCheck: " + p.getSet());
            boolean isLegal = checkSet(p);
            if (isLegal) 
                p.point();
            else 
                p.penalty();
        }
        else {
            p.cancel();
        }
        synchronized (p) {
            p.notifyAll();
        }

    }

    
    /**
     * Checks if it is a legal Set
     */
    private boolean checkSet(Player p) {
        boolean isLegal = false;

        int[] set = new int[env.config.featureSize];
        int[] mySlots = new int[env.config.featureSize];
        LinkedList<Integer> currSet = p.getSet();
        for (int i = 0; i < currSet.size(); i++) {
            int slot = currSet.get(i);
            int card = table.slotToCard[slot];
            set[i] = card;
            mySlots[i] = slot;
        }

        isLegal = env.util.testSet(set);

        if (isLegal) {
            lockAll();
            removeCardsFromTable(mySlots);
            placeCardsOnTable(mySlots);
            updateTimerDisplay(true);
            unlockAll();
        }
    
        return isLegal;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable(int[] currSet) {
        // TODO implement
        for (int i = 0; i < currSet.length; i++) {
            table.removeCard(currSet[i]);
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable(int[] currSet) {
        // TODO implement
        for (int i = 0; i < currSet.length & (!deck.isEmpty()); i++) {
            int cardIndex = (int) (Math.random() * (double) (deck.size()));
            table.placeCard(deck.remove(cardIndex), currSet[i]);
        }
        if (env.config.hints) table.hints();
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement

        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[i] != null) {
                int card = table.slotToCard[i];
                deck.add(card); // return to deck
                table.removeCard(i);
            }
        }
    }

    private void placeAllCardsOnTable() {
        // TODO implement
        int tableS = env.config.tableSize;
        LinkedList<Integer> s = new LinkedList<Integer>(); // list of slots
        for (int j = 0; j < tableS; j++) {
            s.add((Integer) j);
        }

        for (int i = 0; i < tableS & (!deck.isEmpty()); i++) {
            int cardIndex = (int) (Math.random() * (double) (deck.size()));
            int slotIndex = (int) (Math.random() * (double) (s.size()));
            int card = deck.remove(cardIndex);
            int slot = s.remove(slotIndex);
            table.placeCard(card, slot);
        }
        if (env.config.hints) table.hints();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        // TODO implement

        for (Player p : players) {
            try {
                p.terminate();
                threadsArray[p.id].interrupt();
                threadsArray[p.id].join();
            } catch (InterruptedException e) {}
            terminate = true;
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (qPlayerToCheck) {
            if (qPlayerToCheck.isEmpty()) {
                long delta = reshuffleTime - System.currentTimeMillis();
                if (delta <= env.config.turnTimeoutWarningMillis) {
                    try {
                        qPlayerToCheck.wait(10);
                    } catch (InterruptedException e) {}
                } else {
                    try {
                        qPlayerToCheck.wait(1000);
                    } catch (InterruptedException e) {}
                }
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     * reset = false : countdown update
     * reset = true : shuffle time, need to update the timer to 60
     */
    private void updateTimerDisplay(boolean reset) {
        // TODO implement
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        } else {
            long delta = reshuffleTime - System.currentTimeMillis();
            if (delta <= 5000) // warn
            {
                reset = true;
            }
            env.ui.setCountdown(delta, reset);
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    protected int[] announceWinners() {
        // TODO implement
        int max = 0;
        int count = 0;
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() > max) {
                max = players[i].score();
                count = 1;

            } else if (players[i].score() == max) {
                count++;
            }
        }
        int[] winners = new int[count];
        int j = 0;
        for (Player p : players) {
            if (p.score() == max) {
                winners[j] = p.id;
                j++;
            }
        }
        env.ui.announceWinner(winners);
        return(winners);
    }

    public void addPlayerToQ(Player p) {
        synchronized(qPlayerToCheck) {
            qPlayerToCheck.add(p);
            qPlayerToCheck.notifyAll();}
    }

    public Queue<Player> getPlayersQ() {
       return qPlayerToCheck;
    }
}