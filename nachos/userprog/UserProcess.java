package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.awt.print.Pageable;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.NoSuchElementException;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/**
	 * Allocate a new process.
	 */
	public UserProcess() {
//		int numPhysPages = Machine.processor().getNumPhysPages();
		// Will be initialized later
		pageTable = null;
		UserKernel.pidCounterMutex.acquire();
		pid = UserKernel.pidCounter++;
		UserKernel.pidCounterMutex.release();
//		// dummy
//		for (int i = 0; i < numPhysPages; i++)
//			pageTable[i] = new TranslationEntry(i, i, true, false, false, false);
		fileDescriptors = new OpenFile[16];
		fileDescriptors[0] = UserKernel.console.openForReading();
		fileDescriptors[1] = UserKernel.console.openForWriting();

		//part 3
		childrenExitStatus = new HashMap<>();
		children = new HashMap<>();
		parent = null;
		joinCV = new Condition(UserKernel.joinMutex);

	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
	        String name = Machine.getProcessClassName ();

		// If Lib.constructObject is used, it quickly runs out
		// of file descriptors and throws an exception in
		// createClassLoader.  Hack around it by hard-coding
		// creating new processes of the appropriate type.

		if (name.equals ("nachos.userprog.UserProcess")) {
		    return new UserProcess ();
		} else if (name.equals ("nachos.vm.VMProcess")) {
		    return new VMProcess ();
		} else {
		    return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
		}
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		thread = new UThread(this);
		thread.setName(name).fork();
		UserKernel.runningProcessCounterMutex.acquire();
		UserKernel.runningProcessCounter++;
		UserKernel.runningProcessCounterMutex.release();
		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
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
		if (initialVPN >= pageTable.length) {
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
				System.out.println("invalid page, vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				return totalRead;
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

		if (initialVPN >= pageTable.length) {
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
				System.out.println("invalid page, vpn: "+ entry.vpn + "ppn: " + entry.ppn);
				return totalWrite;
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
		return totalWrite;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		Lib.debug(dbgProcess, "loadsection not false");
		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
			//TODO
			Lib.debug(dbgProcess, "1");
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.debug(dbgProcess, "2");
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.debug(dbgProcess, "3");
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			Lib.debug(dbgProcess, "4");
			stringOffset += 1;
		}
		
		Lib.debug(dbgProcess, "load not false");
		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {
		Lib.debug(dbgProcess, "load section starts");
		if (numPages > Machine.processor().getNumPhysPages() || numPages>UserKernel.pagesAvailable.size()) {
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			return false;
		}
		// initialize page table
		pageTable = new TranslationEntry[numPages];
		UserKernel.pagesAvailableMutex.acquire();
		for (int i = 0; i < numPages; i++) {
			pageTable[i] = new TranslationEntry(i,UserKernel.pagesAvailable.remove(), true, false, false, false);
		}
		UserKernel.pagesAvailableMutex.release();
		// acquire the lock before loading
		//UserKernel.pagesAvailableMutex.acquire();
		// load sections
		int counter = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				// get the first available ppn and assign to the page
				boolean readOnly = section.isReadOnly();
				// acquire the lock before loading
				UserKernel.pagesAvailableMutex.acquire();
				try {
					//int ppn = UserKernel.pagesAvailable.removeLast();
					//section.loadPage(i, ppn);
					TranslationEntry translationEntry = pageTable[vpn];
					int ppn = translationEntry.ppn;	
					section.loadPage(i,ppn);
					translationEntry.readOnly = readOnly;
					System.out.println("Page with vpn [" + translationEntry.vpn + "], ppn [" + translationEntry.ppn + "] is read only");
					//pageTable[counter] = new TranslationEntry(vpn, ppn, true, readOnly, false, false);
				} catch (NoSuchElementException e){
					System.out.println("No available physical page for process " + pid);
					unloadSections();
					UserKernel.pagesAvailableMutex.release();
					return false;
				}
				UserKernel.pagesAvailableMutex.release();
				// for now, just assume virtual addresses=physical addresses
				counter++;
			}
		}
		// debug
		/**
		Lib.debug(dbgProcess, "numPages: " + numPages + " counter: " + counter);
		Lib.assertTrue(counter == numPages - 9);
		// load stack pages and argument page, 9 pages in total
		for (int i = counter; i < numPages; i++) {
			// acquire the lock before loading
			UserKernel.pagesAvailableMutex.acquire();
			try {
				int ppn = UserKernel.pagesAvailable.removeLast();
				pageTable[counter] = new TranslationEntry(i, ppn, true, false, false, false);
			} catch (NoSuchElementException e){
				System.out.println("No available physical page for process" + pid);
				unloadSections();
				UserKernel.pagesAvailableMutex.release();
				return false;
			}
			Lib.debug(dbgProcess, "finish load section");
			UserKernel.pagesAvailableMutex.release();
		}
		*/
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		for (int i = 0; i < pageTable.length; i++){
			if (pageTable[i] == null || !pageTable[i].valid){
				continue;
			}
			int ppn = pageTable[i].ppn;
			// acquire mutex lock
			UserKernel.pagesAvailableMutex.acquire();
			UserKernel.pagesAvailable.add(ppn);
			UserKernel.pagesAvailableMutex.release();
			pageTable[i] = null;
		}
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/**
	 * Handle the halt() system call.
	 */
	private int handleHalt() {
		Lib.debug(dbgProcess, "handling halt");
		if (pid == 0) {

			Machine.halt();

			Lib.assertNotReached("Machine.halt() did not halt machine!");
			return 0;
		}
		return -1;
	}

	/**
	 * Handle the exit() system call.
	 */
	private int handleExit(int status) {
	        // Do not remove this call to the autoGrader...
		Machine.autoGrader().finishingCurrentProcess(status);
		// ...and leave it as the top of handleExit so that we
		// can grade your implementation.

		// close all file descriptors
		for (OpenFile openedFile : fileDescriptors) {
			if (openedFile != null) {
				openedFile.close();
				openedFile = null; //need to clear descriptor?
			}
		}
		// unload sections
		unloadSections();
		//close coff
		coff.close();
		// save exit status to childrenExitStatus
		if (parent != null){
			if(exception) {
				parent.childrenExitStatus.put(this, null);
			} else{
				parent.childrenExitStatus.put(this, status);
			}
			// should wake join cv?
			UserKernel.joinMutex.acquire();
			parent.joinCV.wake();
			UserKernel.joinMutex.release();
		}
		// set parent of this process's children to null
		for (UserProcess child : children.values()) {
			child.parent = null;
		}
		// check if this process is the last process
		UserKernel.runningProcessCounterMutex.acquire();
		if (--UserKernel.runningProcessCounter == 0) {
			// terminate kernal
			Kernel.kernel.terminate();
		}
		UserKernel.runningProcessCounterMutex.release();
		KThread.finish();
		Lib.debug(dbgProcess, "UserProcess.handleExit (" + status + ")");
		// for now, unconditionally terminate with just one process
		return 0;
	}

	/****************************PART 1 START****************************/
	// TODO
	/**
	 * Handle the creat() system call.
	 *
	 * Attempt to open the named disk file, creating it if it does not exist,
	 * and return a file descriptor that can be used to access the file. If
	 * the file already exists, creat truncates it.
	 *
	 * Note that creat() can only be used to create files on disk; creat() will
	 * never return a file descriptor referring to a stream.
	 *
	 * Returns the new file descriptor, or -1 if an error occurred.
	 */
	private int handleCreat(int name) {
		String fileName = readVirtualMemoryString(name, 256);
		if (fileName==null) {
			System.out.println("handleCreat: No fileName found from Virtual Memory.");
			return -1;
		}
		OpenFile openFile = Machine.stubFileSystem().open(fileName, true);
		if (openFile==null) {
			System.out.println("handleCreat: No file of fileName found from fileSystem.");
			return -1;
		} else {
			for (int i=2; i<fileDescriptors.length; i++) {
				if (fileDescriptors[i]==null) {
					fileDescriptors[i] = openFile;
					return i;
				}
			}
			openFile.close();
			System.out.println("handleCreat: fileDescriptors reaches the max capacity.");
			return -1;
		}
	}

	/**
	 * Handle the open() system call.
	 *
	 * Attempt to open the named file and return a file descriptor.
	 *
	 * Note that open() can only be used to open files on disk; open() will never
	 * return a file descriptor referring to a stream.
	 *
	 * Returns the new file descriptor, or -1 if an error occurred.
	 */
	private int handleOpen(int name) {
		String fileName = readVirtualMemoryString(name, 256);
		if (fileName==null) {
			System.out.println("handleOpen: No fileName found from Virtual Memory.");
			return -1;
		}
		OpenFile openFile = Machine.stubFileSystem().open(fileName, false);
		if (openFile==null) {
			System.out.println("handleOpen: No file of fileName found from fileSystem.");
			return -1;
		} else {
			for (int i=2; i<fileDescriptors.length; i++) {
				if (fileDescriptors[i]==null) {
					fileDescriptors[i] = openFile;
					return i;
				}
			}
			openFile.close();
			System.out.println("handleOpen: fileDescriptors reaches the max capacity.");
			return -1;
		}
	}

	/**
	 * Handle the read() system call.
	 *
	 * Attempt to read up to count bytes into buffer from the file or stream
	 * referred to by fileDescriptor.
	 *
	 * On success, the number of bytes read is returned. If the file descriptor
	 * refers to a file on disk, the file position is advanced by this number.
	 *
	 * It is not necessarily an error if this number is smaller than the number of
	 * bytes requested. If the file descriptor refers to a file on disk, this
	 * indicates that the end of the file has been reached. If the file descriptor
	 * refers to a stream, this indicates that the fewer bytes are actually
	 * available right now than were requested, but more bytes may become available
	 * in the future. Note that read() never waits for a stream to have more data;
	 * it always returns as much as possible immediately.
	 *
	 * On error, -1 is returned, and the new file position is undefined. This can
	 * happen if fileDescriptor is invalid, if part of the buffer is read-only or
	 * invalid, or if a network stream has been terminated by the remote host and
	 * no more data is available.
	 */
	private int handleRead(int fileDescriptor, int buffer, int count) {
		if (fileDescriptor<0 || fileDescriptor>15) {
			System.out.println("handleRead: fileDescriptor is invalid.");
			return -1;
		}
		OpenFile openFile = fileDescriptors[fileDescriptor];
		if (openFile==null) {
			System.out.println("handleRead: there is no file at given fileDescriptor.");
			return -1;
		}
		byte[] pageSizeArray = new byte[pageSize];
		int readCount = 0;
		while (count > pageSize) {
			int oneTurnRead = openFile.read(pageSizeArray,0,pageSize);
			if (oneTurnRead == 0 ) return readCount;
			if (oneTurnRead < 0) {
				System.out.println("handleRead: openFile read method failure.");
				return -1;
			}
			// Write
			int oneTurnWrite = writeVirtualMemory(buffer,pageSizeArray,0,oneTurnRead);
			if (oneTurnRead < oneTurnWrite) {
				System.out.println("handleRead: write onto VM failure.");
				return -1;
			}
			// Handle stuck in read only page case
			oneTurnRead = Math.min(oneTurnRead, oneTurnWrite);
			System.out.println("handleRead: read/write " + count + " bytes in total, read/write " + oneTurnRead + " byte in this turn");
			buffer += oneTurnRead;
			readCount += oneTurnRead;
			count -= oneTurnRead;
		}
		int oneTurnRead = openFile.read(pageSizeArray,0,count);
		if (oneTurnRead < 0) {
			System.out.println("handleRead: openFile read method failure.");
			return -1;
		}
		int oneTurnWrite = writeVirtualMemory(buffer,pageSizeArray,0,oneTurnRead);
		if (oneTurnRead < oneTurnWrite) {
			System.out.println("handleRead: write onto VM failure.");
			return -1;
		}
		// Handle stuck in read only page case
		oneTurnRead = Math.min(oneTurnRead, oneTurnWrite);
		System.out.println("handleRead: read/write " + oneTurnRead + " bytes in this turn");
		readCount += oneTurnRead;
		count -= oneTurnRead;
//		if (count!=0) {
//			System.out.println("handleRead: not finish reading all.");
//			return -1;
//		}
		return readCount;
	}

	/**
	 * Handle the write() system call.
	 *
	 * Attempt to write up to count bytes from buffer to the file or stream
	 * referred to by fileDescriptor. write() can return before the bytes are
	 * actually flushed to the file or stream. A write to a stream can block,
	 * however, if kernel queues are temporarily full.
	 *
	 * On success, the number of bytes written is returned (zero indicates nothing
	 * was written), and the file position is advanced by this number. It IS an
	 * error if this number is smaller than the number of bytes requested. For
	 * disk files, this indicates that the disk is full. For streams, this
	 * indicates the stream was terminated by the remote host before all the data
	 * was transferred.
	 *
	 * On error, -1 is returned, and the new file position is undefined. This can
	 * happen if fileDescriptor is invalid, if part of the buffer is invalid, or
	 * if a network stream has already been terminated by the remote host.
	 */
	private int handleWrite(int fileDescriptor, int buffer, int count) {
		// System.out.println("count: "+count);
		if (fileDescriptor<0 || fileDescriptor>15) {
			System.out.println("handleWrite: fileDescriptor is invalid.");
			return -1;
		}
		OpenFile openFile = fileDescriptors[fileDescriptor];
		if (openFile==null) {
			System.out.println("handleWrite: there is no file at given fileDescriptor.");
			return -1;
		}
		byte[] pageSizeArray = new byte[pageSize];
		int writeCount = 0;
		while (count > pageSize) {
			int oneTurnRead = readVirtualMemory(buffer,pageSizeArray);
			// System.out.println("One turn read: " + oneTurnRead);
			int oneTurnWrite = openFile.write(pageSizeArray,0,oneTurnRead);
			// should check this case first to make sure we read enough 
			if(oneTurnRead != pageSize){
				System.out.println("NO enough space.");
				return -1;
			}
			if (oneTurnWrite == 0 ) {
				System.out.println("We are here.");
				return writeCount;
			}
			if (oneTurnWrite < 0) {
				System.out.println("handleWrite: openFile write method failure.");
				return -1;
			}
			if (oneTurnRead!=oneTurnWrite) {
				System.out.println("handleWrite: not match read from VM failure.");
				return -1;
			}
			buffer += oneTurnWrite;
			writeCount += oneTurnWrite;
			count -= oneTurnWrite;
		}
		if (count<0) {
			System.out.println("handleWrite: invalid count.");
			return -1;
		}
		pageSizeArray = new byte[count];
		//System.out.println("Start reading rest things buffer: "+buffer+" pageSize: " + pageSizeArray.length);
		//System.out.println(buffer);
		int oneTurnRead = readVirtualMemory(buffer,pageSizeArray);
		int oneTurnWrite = openFile.write(pageSizeArray,0,oneTurnRead);
		if (oneTurnWrite < 0) {
			System.out.println("handleWrite: openFile write method failure.");
			return -1;
		}
		// System.out.println("read: " + oneTurnRead+ " write: "+ oneTurnWrite);
		if (oneTurnRead!=oneTurnWrite) {
			System.out.println("handleWrite: not match read from VM failure.");
			return -1;
		}
		writeCount += oneTurnWrite;
		count -= oneTurnWrite;
		// System.out.println("writeCount: " + writeCount+ " count: " +count);
		if (count!=0) {
			System.out.println("handleWrite: not finish writing all.");
			return -1;
		}
		if (writeCount>pageTable.length*pageSize) {
			System.out.println("Out of address space.");
			return -1;
		}
		// System.out.println(pageTable.length*pageSize);
		return writeCount;
	}

	/**
	 * Handle the close() system call.
	 *
	 * Close a file descriptor, so that it no longer refers to any file or
	 * stream and may be reused. The resources associated with the file
	 * descriptor are released.
	 *
	 * Returns 0 on success, or -1 if an error occurred.
	 */
	private int handleClose(int fileDescriptor) {
		if (fileDescriptor<=1 || fileDescriptor>15 || fileDescriptors[fileDescriptor]==null) {
			System.out.println("handleClose: fileDescriptor is invalid or no file at given fileDescriptor.");
			return -1;
		}
		fileDescriptors[fileDescriptor].close();
		fileDescriptors[fileDescriptor] = null;
		return 0;
	}

	/**
	 * Handle the unlink() system call.
	 *
	 * Delete a file from the file system.
	 *
	 * If another process has the file open, the underlying file system
	 * implementation in StubFileSystem will cleanly handle this situation
	 * (this process will ask the file system to remove the file, but the
	 * file will not actually be deleted by the file system until all
	 * other processes are done with the file).
	 *
	 * Returns 0 on success, or -1 if an error occurred.
	 */
	private int handleUnlink(int name) {
		String fileName = readVirtualMemoryString(name, 256);
		if (fileName==null) {
			System.out.println("handleUnlink: No fileName found from Virtual Memory.");
			return -1;
		}
		int fileDescriptor = -1;
		for (int i=2; i<fileDescriptors.length; i++) {
			if (fileDescriptors[i].getName()==fileName) {
				fileDescriptor = i;
			}
		}
		if (fileDescriptor==-1) {
			System.out.println("handleUnlink: No fileName found from fileDescriptor.");
			return -1;
		}
		int isClosed = handleClose(fileDescriptor);
		boolean isRemoved = ThreadedKernel.fileSystem.remove(fileName);
		if (isRemoved && isClosed==0) {
			return 0;
		} else {
			System.out.println("handleUnlink: Unlink failed.");
			return -1;
		}
	}

	/****************************PART 1 END****************************/


	/****************************PART 3 START****************************/
	// TODO
	/**
	 * Handle the exec() system call.
	 */
	private int handleExec(int file, int argc, int argv) {

		//check inputs
		if (argc < 0) {
			System.out.println("Invalid input for handleExec, argc < 0");
			return -1;
		}
		String filename = readVirtualMemoryString(file,256);
		if (filename == null) {
			System.out.println("Invalid filename for handleExec");
			return -1;
		}
		if (filename.length() < 5) {
			System.out.println("Invalid filename length, length["+filename.length()+"] < 5");
		}
		String extension = filename.substring(filename.length()-5, filename.length());
		//if (extension != ".coff") {
		//	System.out.println("Invalid filename extension, extension["+extension+"] is not .coff");
		//}

		// read arguments
		String[] args = new String[argc];
		byte[] argBuffer = new byte[4];
		for (int i = 0; i < argc; i++) {
			readVirtualMemory(argv + i * 4, argBuffer);
			String cur_arg = readVirtualMemoryString(Lib.bytesToInt(argBuffer, 0),256);
			// check for null argument
			if (cur_arg == null){
				System.out.println("invalid argument from memory, argument is null");
				return -1;
			}
			args[i] = cur_arg;
		}

		// create child process
		UserProcess child = new UserProcess();
		// child is not exited so no exit status
		children.put(child.pid, child);
		child.parent = this;
		// try execute the child
		if (!child.execute(filename, args)) {
			System.out.println("Child execution unsuccessful");
			//childrenStatus.remove(child);
			return -1;
		}
		// Increment of runningProcessCounter in execute
		return child.pid;
	}

	/**
	 * Handle the join() system call.
	 */
	private int handleJoin(int processID, int status) {
		// check if the pid belongs to a child process(valid)
		// out of address space?
		if (!children.containsKey(processID)) {
			System.out.println("handleJoin: Invalid pid [" + processID + "], either the pid doesn't belong to a child process or it has been joined");
			return -1;
		}

		UserProcess child = children.get(processID);

		// check if the child is finished
		if (!childrenExitStatus.containsKey(child)) {
			UserKernel.joinMutex.acquire();
			joinCV.sleep();
			UserKernel.joinMutex.release();
		} else {
			System.out.println("handleJoin:  Child [" + processID + "] already exited with status[" +childrenExitStatus.get(child) + "] before calling join");
		}

		// save the children's exit status if it has one
		Integer exitStatus = childrenExitStatus.get(child);
		if (exitStatus == null) {
			// indicates an exit from unhandled exception
			System.out.println("handleJoin: Child [" + processID + "] exited due to unhandled exception, returning 0.");
			return 0;
		}

		if( status > pageTable.length*pageSize ){
			System.out.println("If the status is invalid, eg. beyong the end of the address space.");
			return -1;
		}	

		byte[] buffer = Lib.bytesFromInt(exitStatus);
		writeVirtualMemory(status, buffer);
		System.out.println("handleJoin: Child [" + processID + "] exited with status[" + exitStatus + "], returning 1.");
		return 1;
	}

	/****************************PART 3 END****************************/

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		Lib.debug(dbgProcess, "handling syscall");
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			return handleExit(a0);
		/** PART 1 **/
		// TODO
		case syscallCreate:
			return handleCreat(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0,a1,a2);
		case syscallWrite:
			return handleWrite(a0,a1,a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		/** PART 3 **/
		// TODO
		case syscallExec:
			return handleExec(a0,a1,a2);
		case syscallJoin:
			return handleJoin(a0,a1);

		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
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
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			Lib.assertNotReached("Unexpected exception");
			// trigger flag
			exception = true;
			handleExit(cause);
		}
	}

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	/** The thread that executes the user-level program. */
	protected UThread thread;

	/** The array contains all fileDescriptor. */
	protected OpenFile[] fileDescriptors;

	private int pid;
    
	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';

	/** Part 3 */
	private HashMap<UserProcess, Integer> childrenExitStatus;

	private HashMap<Integer, UserProcess> children;

	private UserProcess parent;

	private boolean exception = false;

	// each process sleeps/wakes on it's own cv
	private Condition joinCV;



}
