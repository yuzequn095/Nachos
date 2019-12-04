package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
		super.saveState();
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		super.restoreState();
	}

	/**
	 * Initializes page tables for this process so that the executable can be
	 * demand-paged.
	 * 
	 * @return <tt>true</tt> if successful.
	 */
	protected boolean loadSections() {
		//return super.loadSections();
		// Part 1 implementation here
		// initialize page table
		pageTable = new TranslationEntry[numPages];
		VMKernel.pagesAvailableMutex.acquire();
		for (int i = 0; i < numPages; i++) {
			// Here initialize valid to false and not load sections from coff
			pageTable[i] = new TranslationEntry(i, VMKernel.pagesAvailable.remove(), false, false, false, false);
		}
		VMKernel.pagesAvailableMutex.release();
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		super.unloadSections();
	}


	/**
	 * Being called when a page fault is detected, trap into OS
	 * Case1: coff page, load from coff if clean, load from swap if dirty.
	 * Case2: stack/heap page, initialize with 0 if clean, load from swap if dirty.
	 */
	private void handlePageFault(int badVaddr) {
		// Case1
		// Part 1 implemtation
		int vpnCounter = 0;
		int badVpn = Processor.pageFromAddress(badVaddr);

		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			boolean readOnly = section.isReadOnly();

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + 1;
				vpnCounter = vpn;
				// If the page matches the bad vpn
				if (vpn == badVpn) {
					// Acquire lock for shared data structure
					VMKernel.pagesAvailableMutex.acquire();
					// int ppn = VMKernel.pagesAvailable.removeLast();
					try {
						TranslationEntry translationEntry = pageTable[vpn];
						int ppn = translationEntry.ppn;
						// Handle swap in
						if (translationEntry.dirty) {
							handleSwapIn(vpn, ppn);
						} else {
							section.loadPage(i, ppn);
						}
						translationEntry.readOnly = readOnly;
						if (readOnly) {
							System.out.println("Page with vpn [" + translationEntry.vpn + "], ppn [" + translationEntry.ppn + "] is read only");
						}

					} catch (NoSuchElementException e) {
						System.out.println("Exceptopn, No available physical page for process " + pid);
						unloadSections();
						VMKernel.pagesAvailableMutex.release();
						handleException(-1);
						return;
					}
					VMKernel.pagesAvailableMutex.release();
					return;
				}
			}
		}

		// Case 2
		// Part 1 implemtation
		for (int vpn = vpnCounter + 1; vpn < numPages; vpn++) {
			// If the page matches the bad vpn
			if (vpn == badVpn) {
				// Acquire lock for shared data structure
				VMKernel.pagesAvailableMutex.acquire();
				// int ppn = VMKernel.pagesAvailable.removeLast();
				try {
					TranslationEntry translationEntry = pageTable[vpn];
					int ppn = translationEntry.ppn;
					// Handle swap in
					if (translationEntry.dirty) {
						handleSwapIn(vpn, ppn);
					} else {
						// Fill the physical memory with 0
						fillWithZero(ppn);
					}

				} catch (NoSuchElementException e) {
					System.out.println("Exceptopn, No available physical page for process " + pid);
					unloadSections();
					VMKernel.pagesAvailableMutex.release();
					handleException(-1);
					return;
				}
				VMKernel.pagesAvailableMutex.release();
				return;
			}
		}
	}

	private void handleSwapIn(int vpn, int ppn) {
		handleException(-1);
	}

	private void fillWithZero(int ppn) {
		byte[] data = new byte[Processor.pageSize];
		Arrays.fill(data, (byte) 0);
		System.arraycopy(data, 0, Machine.processor().getMemory(), Processor.makeAddress(ppn, 0), Processor.pageSize);
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.debug(dbgProcess, "start rVM");
		// check offset and length
		if(offset < 0 || length < 0 || offset + length > data.length){
			System.out.println("invalid offset:" + offset+" or/and length: "+length+" data.length: " +data.length);
			return 0;
		}
		byte[] memory = Machine.processor().getMemory();
		//check vaddr
		if (vaddr < 0 || vaddr > pageTable.length * pageSize) {
			System.out.println("invalid vaddr");
			return 0;
		}
		// initialize variables
		int initialVPN = Processor.pageFromAddress(vaddr);	//this variable won't be updated
		if (initialVPN >= pageTable.length || initialVPN < 0) {
			System.out.println("invalid initial vaddr, vpn out of bounds");
			return 0;
		}
		TranslationEntry entry = pageTable[initialVPN];
		int pageOffset = Processor.offsetFromAddress(vaddr);
		int paddr = entry.ppn * pageSize + pageOffset;
		int amount = 0;
		int totalRead = 0;
		// before the loop nothing is copied, variables should be at initial value
		// each iteration should read a new page
		while (totalRead < length) {
			// check paddr
			if (paddr < 0 || paddr >= memory.length) {
				System.out.println("physical address out of bound! vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				return totalRead;
			}
			// check if the page is valid
			if (!entry.valid) {
				System.out.println("readVirtualMemory: Page fault on vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				handlePageFault(vaddr);
				// ???? what if the entry is still invalid ????
			}
			// update amount, only updated once
			amount = Math.min(length - totalRead, pageSize - pageOffset);
			// actual copy
			System.arraycopy(memory, paddr, data, offset, amount);
			// update vaddr->entry->pageOffset->paddr
			vaddr += amount;
			//TranslationEntry entryOld = entry;
			//entry = pageTable[Processor.pageFromAddress(vaddr)];
			int curVPN = Processor.pageFromAddress(vaddr);
			if (curVPN >= pageTable.length) {
				System.out.println("invalid vpn out of bounds, vpn: " + curVPN + "maximum: " + pageTable.length + "length: " + length + "total read: " + totalRead);
				return totalRead;
			}
			// for debug
			TranslationEntry entryOld = entry;
			entry = pageTable[Processor.pageFromAddress(vaddr)];
			// debug
			//Lib.assertTrue(entryOld.vpn+1 == entry.vpn);
			pageOffset = Processor.offsetFromAddress(vaddr);
			// debug
			//Lib.assertTrue(pageOffset == 0);
			paddr = entry.ppn * pageSize + pageOffset;
			// update cumulative variables
			offset += amount;
			totalRead += amount;
		}
		return totalRead;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 *
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.debug(dbgProcess,"start wVM");
		// check offset and length first
		if(offset < 0 || length < 0 || offset + length > data.length){
			System.out.println("invalid offset or/and length");
			return 0;
		}
		// write data to
		byte[] memory = Machine.processor().getMemory();
		// check vaddr is valid
		if (vaddr < 0 || vaddr > pageTable.length * pageSize) {
			System.out.println("invalid vaddr");
			return 0;
		}
		// initialize variables
		int initialVPN = Processor.pageFromAddress(vaddr);	//this variable won't be updated

		if (initialVPN >= pageTable.length || initialVPN < 0) {
			System.out.println("invalid initial vaddr, vpn out of bounds");
			return 0;
		}
		TranslationEntry entry = pageTable[initialVPN];
		int pageOffset = Processor.offsetFromAddress(vaddr);
		int paddr = entry.ppn * pageSize + pageOffset;
		int amount = 0;
		int totalWrite = 0;
		// before the loop nothing is copied, variables should be at initial value
		// each iteration should read a new page
		while (totalWrite < length) {
			// check paddr
			if (paddr < 0 || paddr >= memory.length) {
				System.out.println("physical address out of bound! vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				return totalWrite;
			}
			// check if the page is valid
			if (!entry.valid) {
				System.out.println("writeVirtualMemory: Page fault on vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				handlePageFault(vaddr);
			}
			// check if the page is read_only
			if (entry.readOnly) {
				System.out.println("Read-Only page, vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				return totalWrite;
			}
			// update amount, only updated once
			amount = Math.min(length - totalWrite, pageSize - pageOffset);
			// actual copy
			System.arraycopy(data, offset, memory, paddr, amount);
			// update vaddr->entry->pageOffset->paddr
			vaddr += amount;
			//TranslationEntry entryOld = entry;
			//entry = pageTable[Processor.pageFromAddress(vaddr)];
			int curVPN = Processor.pageFromAddress(vaddr);
			if (curVPN >= pageTable.length) {
				System.out.println("invalid vpn out of bounds, vpn: " + curVPN + "maximum: " + pageTable.length);
				return totalWrite;
			}
			// for debug
			TranslationEntry entryOld = entry;
			entry = pageTable[Processor.pageFromAddress(vaddr)];
			// debug
			// Lib.assertTrue(entryOld.vpn+1 == entry.vpn);
			pageOffset = Processor.offsetFromAddress(vaddr);
			// debug
			// Lib.assertTrue(pageOffset == 0);
			paddr = entry.ppn * pageSize + pageOffset;
			// update cumulative variables
			offset += amount;
			totalWrite += amount;
		}
		System.out.println("writeVirtualMemory: total written to VM: [" + totalWrite + "], exit now.");
		return totalWrite;
	}
	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */

	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionPageFault:
			handlePageFault(processor.readRegister(Processor.regBadVAddr));
			break;
		default:
			super.handleException(cause);
			break;
		}
	}

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	private static final char dbgVM = 'v';
}
