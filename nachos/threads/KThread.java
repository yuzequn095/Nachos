package nachos.threads;

import nachos.machine.*;

import java.net.FileNameMap;
import java.util.HashSet;


/**
 * A KThread is a thread that can be used to execute Nachos kernel code. Nachos
 * allows multiple threads to run concurrently.
 * 
 * To create a new thread of execution, first declare a class that implements
 * the <tt>Runnable</tt> interface. That class then implements the <tt>run</tt>
 * method. An instance of the class can then be allocated, passed as an argument
 * when creating <tt>KThread</tt>, and forked. For example, a thread that
 * computes pi could be written as follows:
 * 
 * <p>
 * <blockquote>
 * 
 * <pre>
 * class PiRun implements Runnable {
 * 	public void run() {
 *         // compute pi
 *         ...
 *     }
 * }
 * </pre>
 * 
 * </blockquote>
 * <p>
 * The following code would then create a thread and start it running:
 * 
 * <p>
 * <blockquote>
 * 
 * <pre>
 * PiRun p = new PiRun();
 * new KThread(p).fork();
 * </pre>
 * 
 * </blockquote>
 */
public class KThread {
	/**
	 * Get the current thread.
	 * 
	 * @return the current thread.
	 */
	public static KThread currentThread() {
		Lib.assertTrue(currentThread != null);
		return currentThread;
	}

	/**
	 * Allocate a new <tt>KThread</tt>. If this is the first <tt>KThread</tt>,
	 * create an idle thread as well.
	 */
	public KThread() {
		if (currentThread != null) {
			tcb = new TCB();
		}
		else {
			readyQueue = ThreadedKernel.scheduler.newThreadQueue(false);
			readyQueue.acquire(this);

			currentThread = this;
			tcb = TCB.currentTCB();
			name = "main";
			restoreState();

			createIdleThread();
		}
	}

	/**
	 * Allocate a new KThread.
	 * 
	 * @param target the object whose <tt>run</tt> method is called.
	 */
	public KThread(Runnable target) {
		this();
		this.target = target;
	}

	/**
	 * Set the target of this thread.
	 * 
	 * @param target the object whose <tt>run</tt> method is called.
	 * @return this thread.
	 */
	public KThread setTarget(Runnable target) {
		Lib.assertTrue(status == statusNew);

		this.target = target;
		return this;
	}

	/**
	 * Set the name of this thread. This name is used for debugging purposes
	 * only.
	 * 
	 * @param name the name to give to this thread.
	 * @return this thread.
	 */
	public KThread setName(String name) {
		this.name = name;
		return this;
	}

	/**
	 * Get the name of this thread. This name is used for debugging purposes
	 * only.
	 * 
	 * @return the name given to this thread.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Get the full name of this thread. This includes its name along with its
	 * numerical ID. This name is used for debugging purposes only.
	 * 
	 * @return the full name given to this thread.
	 */
	public String toString() {
		return (name + " (#" + id + ")");
	}

	/**
	 * Deterministically and consistently compare this thread to another thread.
	 */
	public int compareTo(Object o) {
		KThread thread = (KThread) o;

		if (id < thread.id)
			return -1;
		else if (id > thread.id)
			return 1;
		else
			return 0;
	}

	/**
	 * Causes this thread to begin execution. The result is that two threads are
	 * running concurrently: the current thread (which returns from the call to
	 * the <tt>fork</tt> method) and the other thread (which executes its
	 * target's <tt>run</tt> method).
	 */
	public void fork() {
		Lib.assertTrue(status == statusNew);
		Lib.assertTrue(target != null);

		Lib.debug(dbgThread, "Forking thread: " + toString() + " Runnable: "
				+ target);

		boolean intStatus = Machine.interrupt().disable();

		tcb.start(new Runnable() {
			public void run() {
				runThread();
			}
		});

		ready();

		Machine.interrupt().restore(intStatus);
	}

	private void runThread() {
		begin();
		target.run();
		finish();
	}

	private void begin() {
		Lib.debug(dbgThread, "Beginning thread: " + toString());

		Lib.assertTrue(this == currentThread);

		restoreState();

		Machine.interrupt().enable();
	}

	/**
	 * Finish the current thread and schedule it to be destroyed when it is safe
	 * to do so. This method is automatically called when a thread's
	 * <tt>run</tt> method returns, but it may also be called directly.
	 * 
	 * The current thread cannot be immediately destroyed because its stack and
	 * other execution state are still in use. Instead, this thread will be
	 * destroyed automatically by the next thread to run, when it is safe to
	 * delete this thread.
	 */
	public static void finish() {
		Lib.debug(dbgThread, "Finishing thread: " + currentThread.toString());

		Machine.interrupt().disable();

		Machine.autoGrader().finishingCurrentThread();

		Lib.assertTrue(toBeDestroyed == null);
		toBeDestroyed = currentThread;

		currentThread.status = statusFinished;
		if (currentThread().joinedFrom != null) {
			currentThread().joinedFrom.ready();
		}

		sleep();
	}

	/**
	 * Relinquish the CPU if any other thread is ready to run. If so, put the
	 * current thread on the ready queue, so that it will eventually be
	 * rescheuled.
	 * 
	 * <p>
	 * Returns immediately if no other thread is ready to run. Otherwise returns
	 * when the current thread is chosen to run again by
	 * <tt>readyQueue.nextThread()</tt>.
	 * 
	 * <p>
	 * Interrupts are disabled, so that the current thread can atomically add
	 * itself to the ready queue and switch to the next thread. On return,
	 * restores interrupts to the previous state, in case <tt>yield()</tt> was
	 * called with interrupts disabled.
	 */
	public static void yield() {
		Lib.debug(dbgThread, "Yielding thread: " + currentThread.toString());

		Lib.assertTrue(currentThread.status == statusRunning);

		boolean intStatus = Machine.interrupt().disable();

		currentThread.ready();

		runNextThread();

		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Relinquish the CPU, because the current thread has either finished or it
	 * is blocked. This thread must be the current thread.
	 * 
	 * <p>
	 * If the current thread is blocked (on a synchronization primitive, i.e. a
	 * <tt>Semaphore</tt>, <tt>Lock</tt>, or <tt>Condition</tt>), eventually
	 * some thread will wake this thread up, putting it back on the ready queue
	 * so that it can be rescheduled. Otherwise, <tt>finish()</tt> should have
	 * scheduled this thread to be destroyed by the next thread to run.
	 */
	public static void sleep() {
		Lib.debug(dbgThread, "Sleeping thread: " + currentThread.toString());

		Lib.assertTrue(Machine.interrupt().disabled());

		if (currentThread.status != statusFinished)
			currentThread.status = statusBlocked;

		runNextThread();
	}

	/**
	 * Moves this thread to the ready state and adds this to the scheduler's
	 * ready queue.
	 */
	public void ready() {
		Lib.debug(dbgThread, "Ready thread: " + toString());

		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(status != statusReady);

		status = statusReady;
		if (this != idleThread)
			readyQueue.waitForAccess(this);

		Machine.autoGrader().readyThread(this);
	}

	/**
	 * Waits for this thread to finish. If this thread is already finished,
	 * return immediately. This method must only be called once; the second call
	 * is not guaranteed to return. This thread must not be the current thread.
	 */
	public void join() {
		Lib.debug(dbgThread, "Joining to thread: " + toString());
		System.out.println("Joining to thread: " + toString());
		Lib.assertTrue(this != currentThread);
		Lib.assertTrue(!joinedThreads.contains(this));
		Machine.interrupt().disable();
		if (this.status != statusFinished) {
			this.joinedFrom = currentThread();
			System.out.println("Joined from: " + this.joinedFrom);
			joinedThreads.add(this);
/*			if (this.status != statusReady) {
				this.ready();
			}*/
			sleep();
			Machine.interrupt().enable();
			return;
		} else {
			System.out.println("Child process already finished before joining, parent continues.");
			return;
		}
	}

	/**
	 * Create the idle thread. Whenever there are no threads ready to be run,
	 * and <tt>runNextThread()</tt> is called, it will run the idle thread. The
	 * idle thread must never block, and it will only be allowed to run when all
	 * other threads are blocked.
	 * 
	 * <p>
	 * Note that <tt>ready()</tt> never adds the idle thread to the ready set.
	 */
	private static void createIdleThread() {
		Lib.assertTrue(idleThread == null);

		idleThread = new KThread(new Runnable() {
			public void run() {
				while (true)
					KThread.yield();
			}
		});
		idleThread.setName("idle");

		Machine.autoGrader().setIdleThread(idleThread);

		idleThread.fork();
	}

	/**
	 * Determine the next thread to run, then dispatch the CPU to the thread
	 * using <tt>run()</tt>.
	 */
	private static void runNextThread() {
		KThread nextThread = readyQueue.nextThread();
		if (nextThread == null)
			nextThread = idleThread;

		nextThread.run();
	}

	/**
	 * Dispatch the CPU to this thread. Save the state of the current thread,
	 * switch to the new thread by calling <tt>TCB.contextSwitch()</tt>, and
	 * load the state of the new thread. The new thread becomes the current
	 * thread.
	 * 
	 * <p>
	 * If the new thread and the old thread are the same, this method must still
	 * call <tt>saveState()</tt>, <tt>contextSwitch()</tt>, and
	 * <tt>restoreState()</tt>.
	 * 
	 * <p>
	 * The state of the previously running thread must already have been changed
	 * from running to blocked or ready (depending on whether the thread is
	 * sleeping or yielding).
	 * 
	 * @param finishing <tt>true</tt> if the current thread is finished, and
	 * should be destroyed by the new thread.
	 */
	private void run() {
		Lib.assertTrue(Machine.interrupt().disabled());

		Machine.yield();

		currentThread.saveState();

		Lib.debug(dbgThread, "Switching from: " + currentThread.toString()
				+ " to: " + toString());

		currentThread = this;

		tcb.contextSwitch();

		currentThread.restoreState();
	}

	/**
	 * Prepare this thread to be run. Set <tt>status</tt> to
	 * <tt>statusRunning</tt> and check <tt>toBeDestroyed</tt>.
	 */
	protected void restoreState() {
		Lib.debug(dbgThread, "Running thread: " + currentThread.toString());

		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(this == currentThread);
		Lib.assertTrue(tcb == TCB.currentTCB());

		Machine.autoGrader().runningThread(this);

		status = statusRunning;

		if (toBeDestroyed != null) {
			toBeDestroyed.tcb.destroy();
			toBeDestroyed.tcb = null;
			toBeDestroyed = null;
		}
	}

	/**
	 * Prepare this thread to give up the processor. Kernel threads do not need
	 * to do anything here.
	 */
	protected void saveState() {
		Lib.assertTrue(Machine.interrupt().disabled());
		Lib.assertTrue(this == currentThread);
	}

	/**
	 * Get the status
	 */
	protected int getStatus() {
		return this.status;
	}

	private static class PingTest implements Runnable {
		PingTest(int which) {
			this.which = which;
		}

		public void run() {
			for (int i = 0; i < 5; i++) {
				System.out.println("*** thread " + which + " looped " + i
						+ " times");
				currentThread.yield();
			}
		}

		private int which;
	}

	/**
	 * Simple test for the situation where the child finishes before
	 * the parent calls join on it.
     */
	private static void joinTest1() {
		KThread child1 = new KThread(new Runnable() {
			public void run() {
				System.out.println("I (heart) Nachos!");
			}
		});
		child1.setName("child1").fork();

		// We want the child to finish before we call join.  Although
		// our solutions to the problems cannot busy wait, our test
		// programs can!

		for (int i = 0; i < 5; i++) {
			System.out.println("busy...");
			KThread.currentThread().yield();
		}
		System.out.println("Before joining, child 1 should be finished.");
		System.out.println("is it? " + (child1.status == statusFinished));
		child1.join();
		System.out.println("After joining, child1 should be finished.");
		System.out.println("is it? " + (child1.status == statusFinished));
		try {
			Lib.assertTrue((child1.status == statusFinished), " Expected child1 to be finished.");
		}
		catch(Error e) {
			System.out.println("***FAILED***");
		}
		System.out.println("***PASSED***");
	}

	/**
	 * Test for the situation that current thread can call join on
	 * multiple child threads in succession.
	 */
	private static void joinTest2() {
		// Create three child threads
		System.out.println("Start join test 2.");
		KThread child1 = new KThread(new Runnable() {
			public void run() {
				System.out.println("I'm the first child!");
			}
		});
		child1.setName("child1").fork();
		System.out.println("Status 1: "+child1.status);
		child1.join();
		System.out.println("After joining, child1 should be finished.");
		System.out.println("is it? " + (child1.status == statusFinished));
		Lib.assertTrue((child1.status == statusFinished), " Expected child1 to be finished.");
		KThread child2 = new KThread(new Runnable() {
			public void run() {
				System.out.println("I'm the second child!");
			}
		});
              		child2.setName("child2").fork();
		child2.join();
		System.out.println("After joining, child2 should be finished.");
		System.out.println("is it? " + (child2.status == statusFinished));
		Lib.assertTrue((child2.status == statusFinished), " Expected child2 to be finished.");
		KThread child3 = new KThread(new Runnable() {
			public void run() {
				System.out.println("I'm the third child!");
			}
		});
		child3.setName("child3").fork();
		child3.join();
		System.out.println("After joining, child3 should be finished.");
		System.out.println("is it? " + (child3.status == statusFinished));
		Lib.assertTrue((child3.status == statusFinished), " Expected child3 to be finished.");
		System.out.println("***PASSED***");
	}

	/**
	 * Test for the situation that join is called on a thread
	 * multiple times by the parent and nachos should assert.
	 */

	private static void joinTest3() {
		// Create the child
		System.out.println("Start join test 3.");
		KThread child1 = new KThread(new Runnable() {
			public void run() {
				System.out.println("I can't be joined more than once!");
			}
		});
		child1.setName("child1");
		child1.fork();
		child1.join();
		boolean asserted = false;
		try {
			child1.join();
		}
		catch(Error e) {
			asserted = true;
			System.out.println("Asserted on multiple join on child 1");
			System.out.println("***PASSED***");
		}
		finally {
			try {
				Lib.assertTrue(asserted);
			} catch (Error e) {
				System.out.println("***FAILED***");
			}
		}
	}

	/**
	 * Test for the situation that join is called on a thread
	 * multiple times by different threads and nachos should assert.
	 */

	private static void joinTest4() {
		System.out.println("Start join test 4.");
		// Create the child
		KThread child1 = new KThread(new Runnable() {
			public void run() {
				System.out.println("I can't be joined more than once!");
			}
		});
		child1.setName("child1");
		// Create a thread that calls join on child1
		KThread child2 = new KThread(new Runnable() {
			public void run() {
				child1.join();
				System.out.println("Child 1 should be finished." + (child1.status == statusFinished));
			}
		});
		child2.setName("child2");
		child2.fork();
		child1.fork();
		// Busy wait on main
		for (int i = 0; i < 5; i++) {
			System.out.println("busy...");
			KThread.currentThread().yield();
		}
		// Main program call join on child 1 and this should trigger assert
		boolean asserted = false;
		try {
			child1.join();
		} catch (Error e) {
			asserted = true;
			System.out.println("Asserted on multiple join on child 1");
		} finally {
			try {
				Lib.assertTrue(asserted);
			} catch (Error e) {
				System.out.println("***FAILED***");
			} finally {
				System.out.println("***PASSED***");
			}
		}
	}

	/**
	 * Test for the situation that independent pairs of threads can join
	 * with each other without interference.
	 */
	private static void joinTest5() {
		System.out.println("Start join test 5.");
		// Create a child thread
		KThread main  = currentThread();
		KThread child1 = new KThread(new Runnable() {
			public void run() {
				System.out.println("Join with the main thread");
				currentThread().joinedFrom.join();
				System.out.println("Not Printed");
				System.out.println(currentThread());
				Lib.assertTrue(currentThread().status == statusFinished);
			}
		});
		child1.setName("child1");
		child1.fork();
		child1.join();
		try {
			Lib.assertTrue(child1.status == statusBlocked);
		} catch (Error e) {
			System.out.println("***FAILED***");
		}
		System.out.println("child 1 called main.join in it's process. Main continues to execute.");
		System.out.println("***PASSED***");
	}

	/**
	 * Test for the situation that a thread calls join on itself
	 * and nachos asserts.
	 */

	private static void joinTest6() {
		System.out.println("Start join test 6.");
		boolean asserted = false;
		try {
			currentThread().join();
		} catch(Error e) {
			asserted = true;
			System.out.println("Nachos asserted on call join on itself");
		}
		try {
			Lib.assertTrue(asserted);
		} catch (Error e) {
			System.out.println("***FAILED***");
		}
		System.out.println("***PASSED***");
	}

	/**
	 * Tests whether this module is working.
	 */
	public static void selfTest() {
		Lib.debug(dbgThread, "Enter KThread.selfTest");

		new KThread(new PingTest(1)).setName("forked thread").fork();
		new PingTest(0).run();
		// Run join tests
		joinTest1();
		joinTest2();
		joinTest3();
		joinTest4();
		joinTest5();
		joinTest6();
	}

	private static final char dbgThread = 't';

	/**
	 * Additional state used by schedulers.
	 * 
	 * @see nachos.threads.PriorityScheduler.ThreadState
	 */
	public Object schedulingState = null;

	private static final int statusNew = 0;

	private static final int statusReady = 1;

	private static final int statusRunning = 2;

	private static final int statusBlocked = 3;

	private static final int statusFinished = 4;

	/**
	 * The status of this thread. A thread can either be new (not yet forked),
	 * ready (on the ready queue but not running), running, or blocked (not on
	 * the ready queue and not running).
	 */
	private int status = statusNew;

	private String name = "(unnamed thread)";

	private Runnable target;

	private TCB tcb;

	/**
	 * Unique identifer for this thread. Used to deterministically compare
	 * threads.
	 */
	private int id = numCreated++;

	/** Number of times the KThread constructor was called. */
	private static int numCreated = 0;

	private static ThreadQueue readyQueue = null;

	private static KThread currentThread = null;

	private static KThread toBeDestroyed = null;

	private static KThread idleThread = null;

	private static HashSet<KThread> joinedThreads = new HashSet<>();

	private KThread joinedFrom;
}
