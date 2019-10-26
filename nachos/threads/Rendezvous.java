/*
package nachos.threads;

import nachos.machine.*;

*/
/**
 * A <i>Rendezvous</i> allows threads to synchronously exchange values.
 *//*

public class Rendezvous {
    */
/**
     * Allocate a new Rendezvous.
     *//*

    public Rendezvous () {
    }

    */
/**
     * Synchronously exchange a value with another thread.  The first
     * thread A (with value X) to exhange will block waiting for
     * another thread B (with value Y).  When thread B arrives, it
     * will unblock A and the threads will exchange values: value Y
     * will be returned to thread A, and value X will be returned to
     * thread B.
     *
     * Different integer tags are used as different, parallel
     * synchronization points (i.e., threads synchronizing at
     * different tags do not interact with each other).  The same tag
     * can also be used repeatedly for multiple exchanges.
     *
     * @param tag the synchronization tag.
     * @param value the integer to exchange.
     *//*

    public int exchange (int tag, int value) {
	return 0;
    }
}
*/

package nachos.threads;

import nachos.machine.*;

import java.util.HashMap;

/**
 * A <i>Rendezvous</i> allows threads to synchronously exchange values.
 */
public class Rendezvous {
    private Lock lock;
    private Condition ex_cv; // for exchange !
    // private int tag = -1;
    // hashmap
    HashMap<Integer, Integer> pair_tv; // hash map for tag and value
    HashMap<Integer, Condition> pair_tc; // hash map for tag and condition  !
    HashMap<Integer, Boolean> pair_lookup; // hash map for tag and boolean
    boolean exchanging = false;

    /**
     * Allocate a new Rendezvous.
     */
    public Rendezvous () {
        lock = new Lock();
        ex_cv = new Condition(lock); //!
        pair_tv = new HashMap<Integer, Integer>();
        pair_tc = new HashMap<Integer, Condition>(); //!
        pair_lookup = new HashMap<>();
    }

    /**
     * Synchronously exchange a value with another thread.  The first
     * thread A (with value X) to exhange will block waiting for
     * another thread B (with value Y).  When thread B arrives, it
     * will unblock A and the threads will exchange values: value Y
     * will be returned to thread A, and value X will be returned to
     * thread B.
     *
     * Different integer tags are used as different, parallel
     * synchronization points (i.e., threads synchronizing at
     * different tags do not interact with each other).  The same tag
     * can also be used repeatedly for multiple exchanges.
     *
     * @param tag the synchronization tag.
     * @param value the integer to exchange.
     */
    // check hm.contain(tag)
    //      no:
    //          target -> hp
    //          sleep() on wait
    //      yes:
    //          wake A from wait
    //
    public int exchange (int tag, int value) {

        // no such tag
        // lock
        lock.acquire();

        System.out.println("check ex for " + KThread.currentThread().getName() + " ex: " + exchanging);
        while( exchanging ){
            // System.out.println("Find " + KThread.currentThread().getName() + ", but got sleep during exchange happen.");
            ex_cv.sleep(); // additional thread
        }


       System.out.println("thread: " + KThread.currentThread().getName());
       // System.out.println("Contain the key? " + pair_tv.containsKey(tag));
       // System.out.println("Look up the key? " + pair_lookup.get(tag));


        if( !pair_tv.containsKey(tag) || !pair_lookup.get(tag) ){ // no such tag
            System.out.println(KThread.currentThread().getName() + " in if block");
            pair_lookup.put(tag, true); // set the tag in lookup
            pair_tv.put(tag, value); // put tag-value
            Condition tag_cv = new Condition(lock); // cv for same tag !!
            pair_tc.put(tag, tag_cv); // put tag associated with condition
            System.out.println("Gonna sleep");
            tag_cv.sleep(); // sleep for waiting
            exchanging = false; // after t1 wake up
            pair_lookup.put(tag, false); // gonna exchange and set lookup false
            ex_cv.wakeAll(); // why not wake? : new thread will sleep as well
        }

        // tag exists
        // lock
        else{
            System.out.println(KThread.currentThread().getName() + " in else block");
            Condition cur_cv = pair_tc.get(tag); // get the cv for exchange /!
            cur_cv.wake(); // wake the match thread
            exchanging = true; // set exchange flag
            // pair_lookup.put(tag, false);
            System.out.println("Now ex: " + exchanging);
        }

        // get t1.val to t2
        int ret_val = pair_tv.get(tag); // get t1 saved value
        pair_tv.put(tag,value); // update t2 value

        lock.release();

        // System.out.println(KThread.currentThread().getName() + " got return value");
        return ret_val;

    }

    // Place Rendezvous test code inside of the Rendezvous class.

    public static void rendezTest1() {
        final Rendezvous r1 = new Rendezvous();
        final Rendezvous r2 = new Rendezvous();
        final Rendezvous r3 = new Rendezvous();

        KThread t1 = new KThread( new Runnable () {
            public void run() {
                int tag = 0;
                int send = -1;

                System.out.println ("Thread " + KThread.currentThread().getName() + " exchanging " + send);
                int recv = r1.exchange (tag, send);
                //Lib.assertTrue (recv == 1, "Was expecting " + 1 + " but received " + recv);
                System.out.println ("Thread " + KThread.currentThread().getName() + " received " + recv);
            }
        });
        t1.setName("t1");

        KThread t2 = new KThread( new Runnable () {
            public void run() {
                int tag = 0;
                int send = 1;

                System.out.println ("Thread " + KThread.currentThread().getName() + " exchanging " + send);
                int recv = r1.exchange (tag, send);
                //Lib.assertTrue (recv == -1, "Was expecting " + -1 + " but received " + recv);
                System.out.println ("Thread " + KThread.currentThread().getName() + " received " + recv);
            }
        });
        t2.setName("t2");


        KThread t3 = new KThread( new Runnable () {
            public void run() {
                int tag = 1;
                int send = -2;

                System.out.println ("Thread " + KThread.currentThread().getName() + " exchanging " + send);
                int recv = r2.exchange (tag, send);
                // Lib.assertTrue (recv == 1, "Was expecting " + -2 + " but received " + recv);
                System.out.println ("Thread " + KThread.currentThread().getName() + " received " + recv);
            }
        });
        t3.setName("t3");

        KThread t4 = new KThread( new Runnable () {
            public void run() {
                int tag = 1;
                int send = -3;

                System.out.println ("Thread " + KThread.currentThread().getName() + " exchanging " + send);
                int recv = r2.exchange (tag, send);
                // Lib.assertTrue (recv == 1, "Was expecting " + -3 + " but received " + recv);
                System.out.println ("Thread " + KThread.currentThread().getName() + " received " + recv);
            }
        });
        t4.setName("t4");



        KThread t5 = new KThread( new Runnable () {
            public void run() {
                int tag = 2;
                int send = -4;

                System.out.println ("Thread " + KThread.currentThread().getName() + " exchanging " + send);
                int recv = r3.exchange (tag, send);
                // Lib.assertTrue (recv == 1, "Was expecting " + -3 + " but received " + recv);
                System.out.println ("Thread " + KThread.currentThread().getName() + " received " + recv);
            }
        });

        t5.setName("t5");

        KThread t6 = new KThread( new Runnable () {
            public void run() {
                int tag = 2;
                int send = -5;

                System.out.println ("Thread " + KThread.currentThread().getName() + " exchanging " + send);
                int recv = r3.exchange (tag, send);
                // Lib.assertTrue (recv == 1, "Was expecting " + -3 + " but received " + recv);
                System.out.println ("Thread " + KThread.currentThread().getName() + " received " + recv);
            }
        });

        t6.setName("t6");



        t1.fork(); t2.fork(); t3.fork();
        t4.fork();
        t5.fork();
        t6.fork();

        // assumes join is implemented correctly
        t1.join(); t2.join();
        t3.join();
        t4.join();
        t5.join();
        t6.join();
    }

    // Invoke Rendezvous.selfTest() from ThreadedKernel.selfTest()

    public static void selfTest() {
        // place calls to your Rendezvous tests that you implement here
        rendezTest1();
    }
}