package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import javax.swing.*;
import java.util.ArrayList;
import java.util.HashMap;
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
		swapLock = new Lock();
		victims = new ArrayList<>();
		swapPages = new LinkedList<>();
		swapFile = ThreadedKernel.fileSystem.open("swapfile", true);
		spnTotal = 0;
		// victim = 0;

		// initialize manager
		manager = new pageManager[Machine.processor().getNumPhysPages()];
		for(int i = 0; i < Machine.processor().getNumPhysPages(); i++){
			manager[i] = new pageManager(null, null, false);
		}
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

	static ArrayList<TranslationEntry> victims;

	// static int victim;

	static Lock victimLock;

	static OpenFile swapFile;

	static int spnTotal;

	static LinkedList<Integer> swapPages;

	static Lock swapLock;

	static class pageManager {
		private TranslationEntry translationEntry;
		private VMProcess process;
		private boolean isPinned;

		public pageManager(TranslationEntry entry, VMProcess process, boolean pin) {
			translationEntry = entry;
			process = process;
			isPinned = pin;
		}

		public TranslationEntry getEntry() {
			return this.translationEntry;
		}

		public void setEntry(TranslationEntry entry) {
			this.translationEntry = entry;
		}

		public VMProcess getProcess() {
			return this.process;
		}

		public void setProcess(VMProcess process) {
			this.process = process;
		}

		public boolean getPinStatus() {
			return this.isPinned;
		}

		public void setPinStatus(boolean pin) {
			this.isPinned = pin;
		}
	}

	static pageManager[] manager;

}
