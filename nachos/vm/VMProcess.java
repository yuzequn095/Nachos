package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.util.Arrays;
import java.util.HashMap;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
	/**
	 * Allocate a new process.
	 */
	public VMProcess() {
		super();
		vpnSpnMap = new HashMap<>();
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
		System.out.println("load2!!");
		// initialize page table
		pageTable = new TranslationEntry[numPages];
		/**No mutex needed for part 2**/
		//UserKernel.pagesAvailableMutex.acquire();
		for (int i = 0; i < numPages; i++) {
			/** Part 1 implementation here **/
			// Here initialize valid to false and not load sections from coff
			// pageTable[i] = new TranslationEntry(i, UserKernel.pagesAvailable.remove(), false, false, false, false);

			/** Part 2 implementation here **/
			// Here initialize valid to false and use dummy ppn (not allocate physical memory)
			// Initially used negative ppn but failed write10
			pageTable[i] = new TranslationEntry(i, i, false, false, false, false);
		}
		//UserKernel.pagesAvailableMutex.release();
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
		VMKernel.managerLock.acquire();
		System.out.println("Process " + pid + " start to handle page fault!");
		int vpn = Processor.pageFromAddress(badVaddr);
		System.out.println("Vpn at start of handlePageFault: " + vpn);
		// Find the corresponding coff section from badvaddr
		Pair pair = sectionFinder(badVaddr);
		CoffSection section = pair.getSection();
		int sectionPageNumber = pair.getSectionPageNumber();
		boolean readOnly;
		if (section == null) {
			readOnly = false;
		} else {
			readOnly = section.isReadOnly();
		}
		int ppn = -1;
		// Acquire lock for shared data structure
		VMKernel.pagesAvailableMutex.acquire();
		// Check if there's no free physical pages
		if (UserKernel.pagesAvailable.isEmpty()) {
			System.out.println("Run out of physical memory without swap vpn: " + vpn);
			// TODO evict page
			VMKernel.managerLock.acquire();
			ppn = evictPage();
			if (ppn < 0) {
				System.out.println("Evict unsuccessful!");
				VMKernel.pagesAvailableMutex.release();
				return;
			}
		} else {
			// Allocate new physical page
			ppn = UserKernel.pagesAvailable.remove();

		}
		VMKernel.pagesAvailableMutex.release();
		// Initialize translationEntry
//		vpn = Processor.pageFromAddress(badVaddr);
		System.out.println("Vpn before dirty: " + vpn);
		boolean dirty = pageTable[vpn].dirty;
		System.out.println("Newly assigned ppn: " + ppn);
		TranslationEntry translationEntry = new TranslationEntry(vpn, ppn, true, readOnly, true, dirty);
		pageTable[vpn] = translationEntry;
		// Handle swap in
		if (dirty) {
			handleSwapIn(vpn, ppn);
		} else {
			if (section != null) {
				section.loadPage(sectionPageNumber, ppn);
			} else {
				fillWithZero(ppn);
			}
		}
		if (readOnly) {
			System.out.println("Page with vpn [" + translationEntry.vpn + "], ppn [" + translationEntry.ppn + "] is read only");
		}
		VMKernel.manager[ppn].setEntry(translationEntry);
		VMKernel.manager[ppn].setProcess(this);
		VMKernel.managerLock.release();
	}

	private Pair sectionFinder(int badVaddr) {
		int vpnCounter = 0;
		// Case1
		int badVpn = Processor.pageFromAddress(badVaddr);
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				System.out.println("sectionFinder: Looking for bad vpn, vpn: " + vpn);
				vpnCounter = vpn;

				// found bad vpn
				if (vpn == badVpn) {
					System.out.println("sectionFinder: Found bad vpn in section, vpn: " + vpn + "section page number: " + i);
					return new Pair(section, i);
				}
			}
		}
		// Case2
		for (int vpn = vpnCounter + 1; vpn < numPages; vpn++) {
			if (vpn == badVpn) {
				System.out.println("sectionFinder: Found for bad vpn in other pages: " + vpn);
				return new Pair(null, -1);
			}
		}
		return new Pair(null, -1);
	}

	private void handleSwapIn(int vpn, int ppn) {
		System.out.println("Swapping in! target's vpn: [" + vpn + "]," + "ppn: [" + ppn + "]");
		VMKernel.swapLock.acquire();
		int spn = -1;
		if (vpnSpnMap.containsKey(vpn)) {
			spn = vpnSpnMap.get(vpn);
		} else {
			System.out.println("handleSwapIn: SPN table does not contain this vpn! Check for concurrency issue!");
		}
		VMKernel.swapFile.read( spn* pageSize, Machine.processor().getMemory(), Processor.makeAddress(ppn, 0), pageSize);
		VMKernel.swapPages.add(spn);
		vpnSpnMap.remove(vpn);
		VMKernel.swapLock.release();
	}

	private void handleSwapOut(int vpn, int ppn) {
		System.out.println("Swapping out! victim's vpn: [" + vpn + "]," + "ppn: [" + ppn + "]");
		VMKernel.swapLock.acquire();
		int spn = 0;
		if (VMKernel.swapPages.isEmpty()) {
			// assign the new spn
			spn = VMKernel.spnTotal;
			// increase swap file size by one page
			VMKernel.spnTotal++;
		} else {
			// assign spn to
			spn = VMKernel.swapPages.remove();
		}
		System.out.println("handleSwapOut: spn [" + spn + "]");
		// swap out page
		VMKernel.swapFile.write(spn * pageSize, Machine.processor().getMemory(), Processor.makeAddress(ppn, 0), pageSize);
		// update map of the corresponding process
		VMKernel.manager[ppn].getProcess().vpnSpnMap.put(vpn, spn);
		VMKernel.swapLock.release();
	}

	private void fillWithZero(int ppn) {
		byte[] data = new byte[pageSize];
		Arrays.fill(data, (byte) 0);
		System.arraycopy(data, 0, Machine.processor().getMemory(), Processor.makeAddress(ppn, 0), pageSize);
	}

	private int victimFinder() {
		// clock
		int ppn = -1;
		for (int i = 0; i < Machine.processor().getNumPhysPages(); i++) {
			System.out.println("Iterating victim ppn: " + i + "...");
			// TODO check if the page is pinned
			if (VMKernel.manager[i].getPinStatus()) {
				System.out.println("Page pinned! ppn: " + i);
				// check if all pages are pinned
				if (VMKernel.numPagesPinned == numPages) {
					System.out.println("All pages are pinned, process" + pid + " sleep on CV!");
					// make the process sleep on CV
					VMKernel.pinCV.sleep();
				}
				continue;
			}
			TranslationEntry entry = VMKernel.manager[i].getEntry();
			if (!entry.used) {
				ppn = i;
				break;
			}
			entry.used = false;
			VMKernel.manager[i].setEntry(entry);
			if (i == Machine.processor().getNumPhysPages() - 1) {
				i = 0;
			}
		}
		return ppn;
	}

	/**
	 * Evicts a physical page from memory for reuse
	 * @return the ppn of the evicted page for resuse
	 */
	private int evictPage() {
		System.out.println("Starting evict page!");
		int vpn;
		// pick a page to evict
		int ppn = victimFinder();

		TranslationEntry tempEntry = VMKernel.manager[ppn].getEntry();
		vpn = tempEntry.vpn;
		ppn = tempEntry.ppn;
		tempEntry.valid = false;
		Lib.assertTrue(tempEntry.valid == VMKernel.manager[ppn].getEntry().valid);
		System.out.println("Target victim's vpn: [" + vpn + "]," + "ppn: [" + ppn + "]");
		// start to evict the page
		// check of page is dirty
		if (tempEntry.dirty) {
			// swap out dirty page
			handleSwapOut(vpn, ppn);
		}
		// processing complete, exit loop
		System.out.println("Eviction successful!");
		return ppn;
	}

	private void setPin(int ppn) {
		VMKernel.pinLock.acquire();
		VMKernel.manager[ppn].setPinStatus(true);
		VMKernel.numPagesPinned++;
		VMKernel.pinLock.release();
		System.out.println("Set pin done on ppn: " + ppn + " by process " + VMKernel.manager[ppn].getProcess().pid);
	}

	private void releasePin(int ppn) {
		VMKernel.pinLock.acquire();
		VMKernel.manager[ppn].setPinStatus(false);
		VMKernel.numPagesPinned--;
		VMKernel.pinCV.wake();
		VMKernel.pinLock.release();
		System.out.println("Release pin done on ppn: " + ppn + " by process " + VMKernel.manager[ppn].getProcess().pid);
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
		// VMKernel.rwLock.acquire();
		// check offset and length
		if(offset < 0 || length < 0 || offset + length > data.length){
			System.out.println("invalid offset:" + offset+" or/and length: "+length+" data.length: " +data.length);
			// VMKernel.rwLock.release();
			return 0;
		}
		byte[] memory = Machine.processor().getMemory();
		//check vaddr
		if (vaddr < 0 || vaddr > pageTable.length * pageSize) {
			System.out.println("invalid vaddr");
			// VMKernel.rwLock.release();
			return 0;
		}
		// initialize variables
		int initialVPN = Processor.pageFromAddress(vaddr);	//this variable won't be updated
		if (initialVPN >= pageTable.length || initialVPN < 0) {
			System.out.println("invalid initial vaddr, vpn out of bounds");
			// VMKernel.rwLock.release();
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
			// check if the page is valid
			if (!entry.valid) {
				System.out.println("readVirtualMemory: Page fault on vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				System.out.println(vaddr);
				handlePageFault(vaddr);
				// ???? what if the entry is still invalid ????
				// update paddr
				entry = pageTable[entry.vpn];
				paddr = entry.ppn * pageSize + pageOffset;
				// set pin for updated ppn
				System.out.println("readVirtualMemory: Setting pin for updated ppn");
				setPin(entry.ppn);
				System.out.println("readVirtualMemory: After handlePageFault vpn: "+ entry.vpn + "ppn: " + entry.ppn);
			} else {
				// set pin for initial ppn
				System.out.println("readVirtualMemory: Setting pin for initial ppn");
				setPin(entry.ppn);
			}
			// check paddr
			if (paddr < 0 || paddr >= memory.length) {
				System.out.println("physical address out of bound! vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				// release pin when error occur
				System.out.println("readVirtualMemory: Releasing pin because invalid paddr");
				releasePin(entry.ppn);
				// VMKernel.rwLock.release();
				return totalRead;
			}
			// update amount, only updated once
			amount = Math.min(length - totalRead, pageSize - pageOffset);
			// actual copy
			System.arraycopy(memory, paddr, data, offset, amount);
			// release pin
			System.out.println("readVirtualMemory: Releasing pin after reading");
			releasePin(entry.ppn);
			// update vaddr->entry->pageOffset->paddr
			vaddr += amount;
			int curVPN = Processor.pageFromAddress(vaddr);
			if (curVPN >= pageTable.length) {
				System.out.println("invalid vpn out of bounds, vpn: " + curVPN + "maximum: " + pageTable.length + "length: " + length + "total read: " + totalRead);
				// VMKernel.rwLock.release();
				return totalRead;
			}
			entry = pageTable[Processor.pageFromAddress(vaddr)];
			System.out.println("readVirtualMemory: VPN after updating: " + entry.vpn);
			pageOffset = Processor.offsetFromAddress(vaddr);
			paddr = entry.ppn * pageSize + pageOffset;
			// update cumulative variables
			offset += amount;
			totalRead += amount;
		}
		// VMKernel.rwLock.release();
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
		// VMKernel.rwLock.acquire();
		// check offset and length first
		if(offset < 0 || length < 0 || offset + length > data.length){
			System.out.println("invalid offset or/and length");
			// VMKernel.rwLock.release();
			return 0;
		}
		// write data to
		byte[] memory = Machine.processor().getMemory();
		// check vaddr is valid
		if (vaddr < 0 || vaddr > pageTable.length * pageSize) {
			System.out.println("invalid vaddr");
			// VMKernel.rwLock.release();
			return 0;
		}
		// initialize variables
		int initialVPN = Processor.pageFromAddress(vaddr);	//this variable won't be updated

		if (initialVPN >= pageTable.length || initialVPN < 0) {
			System.out.println("invalid initial vaddr, vpn out of bounds");
			// VMKernel.rwLock.release();
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
			// check if the page is valid
			if (!entry.valid) {
				System.out.println("writeVirtualMemory: Page fault on vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				handlePageFault(vaddr);
				// update paddr
				entry = pageTable[entry.vpn];
				paddr = entry.ppn * pageSize + pageOffset;
				// set pin for updated ppn
				System.out.println("writeVirtualMemory: Setting pin for updated ppn");
				setPin(entry.ppn);
				System.out.println("writeVirtualMemory: After handlePageFault vpn: "+ entry.vpn + "ppn: " + entry.ppn);
			} else {
				// set pin for initial ppn
				System.out.println("writeVirtualMemory: Setting pin for initial ppn");
				setPin(entry.ppn);
			}
			// check paddr
			if (paddr < 0 || paddr >= memory.length) {
				System.out.println("physical address out of bound! vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				// release pin when error occur
				System.out.println("writeVirtualMemory: Releasing pin because invalid paddr");
				releasePin(entry.ppn);
				// VMKernel.rwLock.release();
				return totalWrite;
			}
			// check if the page is read_only
			if (entry.readOnly) {
				System.out.println("writeVirtualMemory: Read-Only page, vpn: "+ entry.vpn + " ppn: " + entry.ppn + " ,aborting!");
				// release pin when error occur
				System.out.println("writeVirtualMemory: Releasing pin because page is read only");
				releasePin(entry.ppn);
				// VMKernel.rwLock.release();
				return totalWrite;
			}
			// update amount, only updated once
			amount = Math.min(length - totalWrite, pageSize - pageOffset);
			// actual copy
			System.arraycopy(data, offset, memory, paddr, amount);
			// release pin after write
			System.out.println("writeVirtualMemory: Releasing pin after writing");
			releasePin(entry.ppn);
			// update used bit (Proj3 part2)
			entry.dirty = true;
			// update vaddr->entry->pageOffset->paddr
			vaddr += amount;
			int curVPN = Processor.pageFromAddress(vaddr);
			if (curVPN >= pageTable.length) {
				System.out.println("invalid vpn out of bounds, vpn: " + curVPN + "maximum: " + pageTable.length);
				// VMKernel.rwLock.release();
				return totalWrite;
			}
			entry = pageTable[Processor.pageFromAddress(vaddr)];
			System.out.println("readVirtualMemory: VPN after updating: " + entry.vpn);
			pageOffset = Processor.offsetFromAddress(vaddr);
			paddr = entry.ppn * pageSize + pageOffset;
			// update cumulative variables
			offset += amount;
			totalWrite += amount;
		}
		System.out.println("writeVirtualMemory: total written to VM: [" + totalWrite + "], exit now.");
		// VMKernel.rwLock.release();
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
			System.out.println("Process" + pid + " call handlePageFault from handleException");
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

	private HashMap<Integer, Integer> vpnSpnMap;

	private static class Pair {
		private CoffSection section;
		private int sectionPageNumber;

		Pair(CoffSection section, int i) {
			this.section =section;
			this.sectionPageNumber = i;
		}

		CoffSection getSection() {
			return this.section;
		}

		int getSectionPageNumber() {
			return this.sectionPageNumber;
		}
	}
}
