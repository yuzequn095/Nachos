package nachos.threads;

import nachos.machine.*;
import java.util.*;

/**
 * Uses the hardware timer to provide preemption, and to allow threads to sleep
 * until a certain time.
 */
public class Alarm {
	private LinkedList<TimeCompare> timeQueue = new LinkedList<>();

	/**
	 * Allocate a new Alarm. Set the machine's timer interrupt handler to this
	 * alarm's callback.
	 * 
	 * <p>
	 * <b>Note</b>: Nachos will not function correctly with more than one alarm.
	 */
	public Alarm() {
		Machine.timer().setInterruptHandler(new Runnable() {
			public void run() {
				timerInterrupt();
			}
		});
	}

	// Private helper class
	private class TimeCompare  implements Comparable<TimeCompare>{
		private KThread thread;
		private long time;

		public TimeCompare (KThread thread, long time){
			this.thread = thread;
			this.time = time;
		}

		public int compareTo(TimeCompare timeCompare){
			if (time > timeCompare.time){
				return 1;
			}else if (time < timeCompare.time) {
				return -1;
			}else{
				return 0;
			}
		}
	}

	/**
	 * The timer interrupt handler. This is called by the machine's timer
	 * periodically (approximately every 500 clock ticks). Causes the current
	 * thread to yield, forcing a context switch if there is another thread that
	 * should be run.
	 */
	public void timerInterrupt() {
		KThread.currentThread().yield();
		long currTime = Machine.timer().getTime();
		boolean oriStatus = Machine.interrupt().disable(); // Turn off interrupter
		TimeCompare timeCompare;
		for(java.util.Iterator i = timeQueue.iterator();i.hasNext();){
			timeCompare = (TimeCompare) i.next(); // Get each comparable thread from list to check
			if(timeCompare.time<=currTime){ // If reach wakeup time, remove and ready
				i.remove();
				timeCompare.thread.ready();
			}
		}
		Machine.interrupt().restore(oriStatus); // Restore interrupter
	}

	/**
	 * Put the current thread to sleep for at least <i>x</i> ticks, waking it up
	 * in the timer interrupt handler. The thread must be woken up (placed in
	 * the scheduler ready set) during the first timer interrupt where
	 * 
	 * <p>
	 * <blockquote> (current time) >= (WaitUntil called time)+(x) </blockquote>
	 * 
	 * @param x the minimum number of clock ticks to wait.
	 * 
	 * @see nachos.machine.Timer#getTime()
	 */
	public void waitUntil(long x) {
		// for now, cheat just to get something working (busy waiting is bad)
		long wakeTime = Machine.timer().getTime() + x;
		while (wakeTime > Machine.timer().getTime())
			KThread.yield();
		boolean oriStatus = Machine.interrupt().disable(); // Turn off interrupter
		TimeCompare timeCompare = new TimeCompare(KThread.currentThread(), wakeTime);
		timeQueue.add(timeCompare); // Add thread to the list
		KThread.sleep(); // Sleep the thread
		Machine.interrupt().restore(oriStatus); // Restore interrupter
	}

	/**
	 * Cancel any timer set by <i>thread</i>, effectively waking
	 * 	 * up the thread immediately (placing it in the scheduler
	 * 	 * ready set) and returning true.  If <i>thread</i> has no
	 * 	 * timer set, return false.
	 * 
	 * <p>
	 * @param thread the thread whose timer should be cancelled.
	 */
	public boolean cancel(KThread thread) {
		for(java.util.Iterator i = timeQueue.iterator();i.hasNext();){
			TimeCompare timeCompare = (TimeCompare) i.next(); // Get each comparable thread from list to check
			if(timeCompare.thread==thread){ // If the thread has timer set remove and ready
				i.remove();
				timeCompare.thread.ready();
				return true;
			}
		}
		return false;
	}

	// Add Alarm testing code to the Alarm class
	public static void alarmTest1() {
		int durations[] = {1000, 10*1000, 100*1000};
		long t0, t1;

		for (int d : durations) {
			t0 = Machine.timer().getTime();
			ThreadedKernel.alarm.waitUntil (d);
			t1 = Machine.timer().getTime();
			System.out.println ("alarmTest1: waited for " + (t1 - t0) + " ticks");
		}
	}

	// Implement more test methods here ...
	// Invoke Alarm.selfTest() from ThreadedKernel.selfTest()
	public static void selfTest() {
		alarmTest1();
		// Invoke your other test methods here ...
	}
}
