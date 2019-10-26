package nachos.threads;

import nachos.machine.*;

import java.util.LinkedList;
import java.util.concurrent.CancellationException;


/**
 * An implementation of condition variables that disables interrupt()s for
 * synchronization.
 *
 * <p>
 * You must implement this.
 *
 * @see nachos.threads.Condition
 */
public class Condition2 {
	/**
	 * Allocate a new condition variable.
	 *
	 * @param conditionLock the lock associated with this condition variable.
	 * The current thread must hold this lock whenever it uses <tt>sleep()</tt>,
	 * <tt>wake()</tt>, or <tt>wakeAll()</tt>.
	 */

	private static int statusReady = 1;

	public Condition2(Lock conditionLock) {

		this.conditionLock = conditionLock;
		// queue for storing threads
		this.conditionQueue = new LinkedList<KThread>();
	}

	/**
	 * Atomically release the associated lock and go to sleep on this condition
	 * variable until another thread wakes it using <tt>wake()</tt>. The current
	 * thread must hold the associated lock. The thread will automatically
	 * reacquire the lock before <tt>sleep()</tt> returns.
	 */
	/*
	 * general idea:
	 * disable interrupt
	 * make thread wait on CV
	 * put thread to sleep
	 * enable interrupt
	 * ! where to release and acquire
	 */

	public void sleep() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		// first we need to disable interrupt
		// Machine.interrupt().disable();
		boolean intStatus = Machine.interrupt().disable();


		// release lock after disable interrupt in case unexpect things happen
		conditionLock.release();

		// make thread wait on CV
		KThread cur_thread = KThread.currentThread();
		conditionQueue.add(cur_thread);

		// put thread to sleep
		cur_thread.sleep();

		conditionLock.acquire();

		// finally enable interrupt
		// Machine.interrupt().enable();
		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up at most one thread sleeping on this condition variable. The
	 * current thread must hold the associated lock.
	 */
	public void wake() {

		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		// disable interrupt first
		// Machine.interrupt().disable();
		boolean intStatus = Machine.interrupt().disable();


		// Check queue
		if (!conditionQueue.isEmpty()) {
			// get the first thread( at most one )
			KThread wake_thread = conditionQueue.pollFirst();
			// System.out.println("Now thread: " + wake_thread.status);
			// update status
			ThreadedKernel.alarm.cancel(wake_thread); // break timer for part 4
			if(wake_thread.getStatus() != statusReady) {
				wake_thread.ready();
			}

		}

		// enable interrupt
		// Machine.interrupt().enable();
		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Wake up all threads sleeping on this condition variable. The current
	 * thread must hold the associated lock.
	 */
	public void wakeAll() {
		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		// disable interrupt first
		// Machine.interrupt().disabled();
		boolean intStatus = Machine.interrupt().disable();
		// disable & enable in wake() already
		// disable interrupt first
		// Machine.interrupt().disabled();
		// for each thread in queue
		for( KThread each_thread:conditionQueue){
			conditionQueue.remove(each_thread); // remove from waiting
			each_thread.ready(); // update status
			ThreadedKernel.alarm.cancel(each_thread);// for part 4
		}

		// enable interrupt
		Machine.interrupt().restore(intStatus);
	}

	/**
	 * Atomically release the associated lock and go to sleep on
	 * this condition variable until either (1) another thread
	 * wakes it using <tt>wake()</tt>, or (2) the specified
	 * <i>timeout</i> elapses.  The current thread must hold the
	 * associated lock.  The thread will automatically reacquire
	 * the lock before <tt>sleep()</tt> returns.
	 */

	// Will implement in part.4
	public void sleepFor(long timeout) {

		Lib.assertTrue(conditionLock.isHeldByCurrentThread());

		boolean intStatus = Machine.interrupt().disable();

		conditionLock.release();

		KThread sleep_thread = KThread.currentThread();

		conditionQueue.add(sleep_thread);

		ThreadedKernel.alarm.waitUntil(timeout);

		//ThreadedKernel.alarm.cancel(sleep_thread);

		if(conditionQueue.contains(sleep_thread)){
			conditionQueue.remove(sleep_thread);
		}

		conditionLock.acquire();

		Machine.interrupt().restore(intStatus);

	}

	private Lock conditionLock;
	// set a queue for storing threads
	private LinkedList<KThread> conditionQueue;

	// Place Condition2 testing code in the Condition2 class.

	// Example of the "interlock" pattern where two threads strictly
	// alternate their execution with each other using a condition
	// variable.  (Also see the slide showing this pattern at the end
	// of Lecture 6.)

	private static class InterlockTest {
		private static Lock lock;
		private static Condition2 cv;

		private static class Interlocker implements Runnable {
			public void run () {
				lock.acquire();
				for (int i = 0; i < 10; i++) {
					System.out.println(KThread.currentThread().getName());
					// System.out.println("now wake");
					cv.wake();   // signal
					// System.out.println("now sleep");

					cv.sleep();  // wait
				}
				lock.release();
			}
		}

		public InterlockTest () {
			System.out.println("Testing");
			lock = new Lock();
			cv = new Condition2(lock);

			KThread ping = new KThread(new Interlocker());
			ping.setName("ping");
			KThread pong = new KThread(new Interlocker());
			pong.setName("pong");

			ping.fork();
			pong.fork();

			// We need to wait for ping to finish, and the proper way
			// to do so is to join on ping.  (Note that, when ping is
			// done, pong is sleeping on the condition variable; if we
			// were also to join on pong, we would block forever.)
			// For this to work, join must be implemented.  If you
			// have not implemented join yet, then comment out the
			// call to join and instead uncomment the loop with
			// yields; the loop has the same effect, but is a kludgy
			// way to do it.
			ping.join();
			// for (int i = 0; i < 50; i++) { KThread.currentThread().yield(); }
		}
	}

	// Place Condition2 test code inside of the Condition2 class.

	// Test programs should have exactly the same behavior with the
	// Condition and Condition2 classes.  You can first try a test with
	// Condition, which is already provided for you, and then try it
	// with Condition2, which you are implementing, and compare their
	// behavior.

	// Do not use this test program as your first Condition2 test.
	// First test it with more basic test programs to verify specific
	// functionality.

	public static void cvTest5() {
		final Lock lock = new Lock();
		// final Condition empty = new Condition(lock);
		final Condition2 empty = new Condition2(lock);
		final LinkedList<Integer> list = new LinkedList<>();

		KThread consumer = new KThread( new Runnable () {
			public void run() {
				lock.acquire();
				while(list.isEmpty()){
					empty.sleep();
				}
				Lib.assertTrue(list.size() == 5, "List should have 5 values.");
				while(!list.isEmpty()) {
					// context swith for the fun of it
					KThread.currentThread().yield();
					System.out.println("Removed " + list.removeFirst());
				}
				lock.release();
			}
		});

		KThread producer = new KThread( new Runnable () {
			public void run() {
				lock.acquire();
				for (int i = 0; i < 5; i++) {
					list.add(i);
					System.out.println("Added " + i);
					// context swith for the fun of it
					KThread.currentThread().yield();
				}
				empty.wake();
				lock.release();
			}
		});

		consumer.setName("Consumer");
		producer.setName("Producer");
		consumer.fork();
		producer.fork();

		// We need to wait for the consumer and producer to finish,
		// and the proper way to do so is to join on them.  For this
		// to work, join must be implemented.  If you have not
		// implemented join yet, then comment out the calls to join
		// and instead uncomment the loop with yield; the loop has the
		// same effect, but is a kludgy way to do it.
		consumer.join();
		producer.join();
		//for (int i = 0; i < 50; i++) { KThread.currentThread().yield(); }
	}

	// Place sleepFor test code inside of the Condition2 class.

	private static void sleepForTest1 () {
		Lock lock = new Lock();
		Condition2 cv = new Condition2(lock);

		lock.acquire();
		long t0 = Machine.timer().getTime();
		System.out.println (KThread.currentThread().getName() + " sleeping");
		// no other thread will wake us up, so we should time out
		cv.sleepFor(2000);
		long t1 = Machine.timer().getTime();
		System.out.println (KThread.currentThread().getName() +
				" woke up, slept for " + (t1 - t0) + " ticks");
		lock.release();
	}



	private static class sleepForTest2 {
		private static Lock lock;
		private static Condition2 cv;

		private static class sleepT implements Runnable {
			public void run () {
				lock.acquire();

				long t0 = Machine.timer().getTime();
				System.out.println("t0: " + t0);

				System.out.println("I am a thread and going to sleep.");
				System.out.println (KThread.currentThread().getName() + " sleeping");
				cv.sleepFor(500000000);
				// cv.wake();
				// ThreadedKernel.alarm.cancel(KThread.currentThread());

				long t1 = Machine.timer().getTime();
				System.out.println("t1: " + t1);

				System.out.println (KThread.currentThread().getName() +
						" woke up, slept for " + (t1 - t0) + " ticks");


				lock.release();

			}
		}

		private static class wakeT implements Runnable {
			public void run () {
				lock.acquire();

				//long t0 = Machine.timer().getTime();
				//System.out.println("t0: " + t0);

				System.out.println("I am a thread and going to wakeup.");
				//System.out.println (KThread.currentThread().getName() + " sleeping");
				//cv.sleepFor(500000000);
				cv.wake();
				// ThreadedKernel.alarm.cancel(KThread.currentThread());

				//long t1 = Machine.timer().getTime();
				//System.out.println("t1: " + t1);

				//System.out.println (KThread.currentThread().getName() +
						//" woke up, slept for " + (t1 - t0) + " ticks");


				lock.release();

			}
		}

		public sleepForTest2 () {
			System.out.println("Testing");
			lock = new Lock();
			cv = new Condition2(lock);

			//lock.acquire();

			KThread sl = new KThread(new sleepT());
			sl.setName("sl");
			KThread sw = new KThread(new wakeT());
			sw.setName("wk");


			// System.out.println("gonna fork");
			sl.fork();
			sw.fork();

			// System.out.println("gonna join");
			sl.join();
			sw.join();

			// cv.wake();

			//lock.release();

		}
	}


	// Invoke Condition2.selfTest() from ThreadedKernel.selfTest()

	public static void selfTest() {
		System.out.println("Start test");
		// new InterlockTest();
		// cvTest5();
		// sleepForTest1();
		new sleepForTest2();
	}
}
