
import java.io.*;
import java.security.*;
import java.sql.Blob;
import java.util.*;
import java.util.concurrent.*;

import javax.lang.model.util.ElementScanner14;
import javax.print.attribute.standard.RequestingUserName;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class Pirate {

	String fileName;
	int numCPUs;
	int timeoutMillis;
	Dispatcher dispatcher;
	int roundNum;

	/* Queue for inputs to be processed */
	LinkedList<WorkUnit> workQueue;

	/* Queue for processed outputs */
	LinkedList<WorkUnit> resQueue;
	LinkedList<WorkUnit> first_round;
	LinkedList<Integer> final_L;

	/* Mutex to protect input queue */
	Semaphore wqMutex;

	/* Mutex to protect output queue */
	Semaphore rsMutex;

	public Pirate(String fileName, int N, int timeout) {
		this.fileName = fileName;
		this.numCPUs = N;
		this.timeoutMillis = timeout;
		this.roundNum = 0;

		/* Now build the other data structures */
		workQueue = new LinkedList<WorkUnit>();
		resQueue = new LinkedList<WorkUnit>();
		first_round = new LinkedList<WorkUnit>();
		final_L = new LinkedList<Integer>();

		wqMutex = new Semaphore(1);
		rsMutex = new Semaphore(1);

		/* Construct the dispatcher which will also start the worker threads */
		this.dispatcher = new Dispatcher(workQueue, resQueue, wqMutex, rsMutex, N, timeout);
	}

	private void __initWorkQueue() throws InterruptedException {
		/* The fileName parameter contains the full path to the input file */
		Path inputFile = Paths.get(fileName);

		/* Attempt to open the input file, if it exists */
		if (Files.exists(inputFile)) {

			/* It appears to exists, so open file */
			File fHandle = inputFile.toFile();

			/* Use a buffered reader to be a bit faster on the I/O */
			try (BufferedReader in = new BufferedReader(new FileReader(fHandle))) {

				String line;

				/* Pass each line read in input to the dehasher */
				while ((line = in.readLine()) != null) {
					WorkUnit work = new WorkUnit(line);
					dispatcher.addWork(work);
				}

			} catch (FileNotFoundException e) {
				System.err.println("Input file does not exist.");
				e.printStackTrace();
			} catch (IOException e) {
				System.err.println("Unable to open input file for read operation.");
				e.printStackTrace();
			}

		} else {
			System.err.println("Input file does not exist. Exiting.");
		}

	}

	private void __prepareCompoundWork() throws InterruptedException {
		/*
		 * This function will execute when no pending work exists. But as it goes along,
		 * worker threads might start crunching data. Hence, copy over all the result so
		 * far.
		 */

		ArrayList<Integer> L = new ArrayList<Integer>();
		ArrayList<String> uncracked = new ArrayList<String>();

		for (WorkUnit w : resQueue) {
			String res = w.getResult();

			if (res != null) {
				L.add(Integer.parseInt(res));
				final_L.add(w.getValue());
				/* We might as well print this first round of results already */
				first_round.add(w);
			} else {
				uncracked.add(w.getHash());
			}
		}

		/* Done splitting result -- we can clean up the result queue */
		resQueue.clear();

		/* Sort list L of integers */
		Collections.sort(L);

		/*
		 * Possibly the worst way of doing this. Generate all the possible tuples of the
		 * form <a, b, hash> to be cracked. The work queue might explode after this. A
		 * work-queue pruning strategy is required to meet some reasonable timing
		 * constraints.
		 */
		int len = L.size();
		for (int i = 0; i < len - 1; ++i) {
			for (int j = i + 1; j < len; ++j) {
				for (String h : uncracked) {
					WorkUnit work = new WorkUnit(h, L.get(i), L.get(j));
					dispatcher.addWork(work);
				}
			}
		}

	}

	private LinkedList<WorkUnit> __postProcessResult() throws InterruptedException {
		HashMap<String, Boolean> uncrackable = new HashMap<String, Boolean>();
		LinkedList<WorkUnit> temp = new LinkedList<WorkUnit>();
		LinkedList<String> printResult = new LinkedList<String>();

		for (WorkUnit w : resQueue) {
			String res = w.getResult();
			String h = w.getHash();

			/* Add all of them for now */
			uncrackable.put(h, true);
		}

		/* Done! add result */
		for (WorkUnit w : resQueue) {
			String res = w.getResult();
			String h = w.getHash();

			if (res != null) {
				printResult.add(res);
				final_L.add(w.getValue());
				temp.add(w);
				/* Remove what we know has been cracked */
				uncrackable.remove(h);
			}
		}

		/* Print result */
		// if (printResult.size() != 0) {
		// System.out.println("---- Round " + roundNum + " ----");
		// for (String s : printResult) {
		// System.out.println(s);
		// }
		// }

		// print first round result(crackable in first round)
		// if (roundNum == 0) {
		// for (WorkUnit w : first_round)
		// System.out.println(w);
		// }

		/* Print the uncrackable hashes */
		for (String h : uncrackable.keySet()) {
			temp.add(new WorkUnit(h));
			// System.out.println(h);
		}
		roundNum++;
		if (uncrackable.size() != 0)
			return temp;
		return new LinkedList<WorkUnit>();
	}

	public void findTreasure() throws InterruptedException {

		/* Read the input file and initialize the input queue */

		__initWorkQueue();

		/* Dispatch work and wait for completion of current stage */
		dispatcher.dispatch();

		rsMutex.acquire();

		/* We have the result. Generate the new work queue to crack compound hashes */
		__prepareCompoundWork();

		rsMutex.release();

		/* Dispatch work and wait for completion of current stage */
		dispatcher.dispatch();

		// /* Use a hash map to prune the outpur result */
		rsMutex.acquire();
		LinkedList<WorkUnit> tempList = __postProcessResult();
		rsMutex.release();

		while (tempList.size() != 0) {

			rsMutex.acquire();

			// System.out.println("len = " + tempList.size());
			resQueue.clear();

			ArrayList<Integer> L = new ArrayList<Integer>();
			ArrayList<String> uncracked = new ArrayList<String>();

			for (WorkUnit w : tempList) {
				String res = w.getResult();

				if (res != null) {
					L.add(w.getValue());

					/* We might as well print this first round of results already */
					first_round.add(w);
				} else {
					uncracked.add(w.getHash());
				}
			}

			/* Done splitting result -- we can clean up the result queue */
			resQueue.clear();

			/* Sort list L of integers */
			Collections.sort(L);
			int len_counter = 0;
			int len = L.size();
			for (int i = 0; i < len - 1; ++i) {
				for (int j = i + 1; j < len; ++j) {
					for (String h : uncracked) {
						WorkUnit work = new WorkUnit(h, L.get(i), L.get(j));
						dispatcher.addWork(work);
						len_counter++;
					}
				}
			}
			rsMutex.release();

			dispatcher.dispatch();

			while (resQueue.size() < len_counter)
				;

			rsMutex.acquire();
			tempList = __postProcessResult();
			rsMutex.release();

			dispatcher.dispatch();
		}
		dispatcher.dispatch();

		// /* Done! Terminate the dispatcher (and any worker thread with that) */
		dispatcher.terminate();
		Collections.sort(final_L);

	}

	public void printTreasure(String filePath) throws FileNotFoundException {
		RandomAccessFile file = new RandomAccessFile(filePath, "rw");
		String result = "";
		try {
			for (int i : final_L) {
				file.seek(i);
				result += (char) (file.read());
			}
		} catch (IOException e) {
		}

		// System.out.print("\nTreasure: [");
		System.out.print(result);
		// System.out.print("]");
	}

	/* Entry point of the code */
	public static void main(String[] args) throws InterruptedException {
		/* Read path of input file */
		String inputFile = args[0];

		/* Read number of available CPUs */
		int N = Integer.parseInt(args[1]);

		/* If it exists, read in timeout, default to 10 seconds otherwise */
		int timeoutMillis = 100000;
		if (args.length > 2) {
			timeoutMillis = Integer.parseInt(args[2]);
		}

		/* Construct the dispatcher with all the necessary parameters */
		Pirate thePirate = new Pirate(inputFile, N, timeoutMillis);

		/* Start the work */
		thePirate.findTreasure();

		try {
			thePirate.printTreasure(args[3]);
		} catch (FileNotFoundException e) {
		}

	}

}

/* END -- Q1BSR1QgUmVuYXRvIE1hbmN1c28= */
