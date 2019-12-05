package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.util.ArrayList;
import java.util.LinkedList;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {
	/**
	 * Allocate a new VM kernel.
	 */
	public VMKernel() {
		super();
	}

	/**
	 * Initialize this kernel.
	 */
	public void initialize(String[] args) {
		super.initialize(args);
		pagesAvailableMutex = new Lock();
		victimLock = new Lock();
		victims = new ArrayList<>();
		swapFile = ThreadedKernel.fileSystem.open("swapfile", true);
		spnTotal = 0;
	}

	/**
	 * Test this kernel.
	 */
	public void selfTest() {
		super.selfTest();
	}

	/**
	 * Start running user programs.
	 */
	public void run() {
		super.run();
	}

	/**
	 * Terminate this kernel. Never returns.
	 */
	public void terminate() {
		super.terminate();
	}

	// dummy variables to make javac smarter
	private static VMProcess dummy1 = null;

	private static final char dbgVM = 'v';

//	protected static Lock pagesAvailableMutex;
//
//	protected static LinkedList<Integer> pagesAvailable = new LinkedList<>();
//
//	protected static int pidCounter;
//
//	protected static Lock pidCounterMutex;
//
//	protected static int runningProcessCounter;
//
//	protected static Lock runningProcessCounterMutex;

	static ArrayList<Integer> victims;

	static Lock victimLock;

	static OpenFile swapFile;

	static int spnTotal;

}
