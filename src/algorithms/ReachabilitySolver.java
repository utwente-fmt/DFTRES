package algorithms;

import java.util.concurrent.atomic.DoubleAccumulator;

public class ReachabilitySolver
{
	private static final double THRESHOLD = 1e-11;

	private double[][] T, tmp;
	private final double[][] Ttransp;
	private final DoubleAccumulator maxRem;

	private class Worker implements Runnable {
		private int minRow, maxRow;
		public boolean done;

		public Worker(int min, int max)
		{
			minRow = min;
			maxRow = max;
		}

		public void run()
		{
			synchronized(maxRem) {
				done = true;
				maxRem.notifyAll();
				while (done) {
					try {
						maxRem.wait();
					} catch (InterruptedException e) {
					}
				}
			}
			while (T != null) {
				iteration(minRow, maxRow);
				synchronized(maxRem) {
					done = true;
					maxRem.notifyAll();
					while (done) {
						try {
							maxRem.wait();
						} catch (InterruptedException e) {
						}
					}
				}
			}
		}
	}

	public ReachabilitySolver(double[][] T)
	{
		this.T = T;
		tmp = new double[T.length][T[0].length];
		Ttransp = new double[T[0].length][T.length];
		maxRem = new DoubleAccumulator(Double::max, 0);
	}

	private ReachabilitySolver(ReachabilitySolver source, int min, int max)
	{
		T = source.T;
		tmp = source.tmp;
		Ttransp = source.Ttransp;
		maxRem = source.maxRem;
	}

	public double[][] solve(int maxIts)
	{
		if (T.length <= 300) {
			singleCoreSolve(maxIts);
		} else {
			int cores = Runtime.getRuntime().availableProcessors();
			if (cores > 2)
				cores -= 1;
			Worker[] workers = new Worker[cores];

			Runnable r;
			for (int i = 0; i < cores - 1; i++) {
				workers[i] = new Worker(
						i * (T.length / cores),
						(i + 1) * (T.length / cores));
				new Thread(workers[i]).start();
			}
			workers[cores - 1] = new Worker(
					(cores - 1) * (T.length / cores),
					T.length);
			new Thread(workers[cores - 1]).start();
			multiCoreSolve(maxIts, workers);
		}
		return tmp;
	}

	public void singleCoreSolve(int maxIts)
	{
		long startTime = System.nanoTime();
		maxRem.accumulate(Double.POSITIVE_INFINITY);
		for (int i = 0; i < maxIts && maxRem.get() > THRESHOLD; i++) {
			maxRem.reset();
			preIteration();
			iteration(0, T.length);
			double[][] t1 = tmp; tmp = T; T = t1;
			long endTime = System.nanoTime();
		}
		tmp = T;
	}

	public void multiCoreSolve(int maxIts, Worker[] workers)
	{
		long startTime = System.nanoTime();
		maxRem.accumulate(Double.POSITIVE_INFINITY);
		synchronized(maxRem) {
			for (int j = 0; j < workers.length; j++) {
				while (!workers[j].done) {
					try {
						maxRem.wait();
					} catch (InterruptedException e) {
					}
				}
			}
		}
		for (int i = 0; i < maxIts && maxRem.get() > THRESHOLD; i++) {
			maxRem.reset();
			preIteration();
			synchronized(maxRem) {
				for (int j = 0; j < workers.length; j++)
					workers[j].done = false;
				maxRem.notifyAll();
				for (int j = 0; j < workers.length; j++) {
					while (!workers[j].done) {
						try {
							maxRem.wait();
						} catch (InterruptedException e) {
						}
					}
				}
			}
			double[][] t1 = tmp; tmp = T; T = t1;
			long endTime = System.nanoTime();
		}
		tmp = T;
		T = null;
		synchronized(maxRem) {
			for (int j = 0; j < workers.length; j++)
				workers[j].done = false;
			maxRem.notifyAll();
		}
	}

	private void preIteration()
	{
		for (int i = 0; i < T.length / 8; i++) {
			for (int j = 0; j < Ttransp.length; j++) {
				Ttransp[j][8*i+0] = T[8*i+0][j];
				Ttransp[j][8*i+1] = T[8*i+1][j];
				Ttransp[j][8*i+2] = T[8*i+2][j];
				Ttransp[j][8*i+3] = T[8*i+3][j];
				Ttransp[j][8*i+4] = T[8*i+4][j];
				Ttransp[j][8*i+5] = T[8*i+5][j];
				Ttransp[j][8*i+6] = T[8*i+6][j];
				Ttransp[j][8*i+7] = T[8*i+7][j];
			}
		}
		for (int i = 8 * (T.length / 8); i < T.length; i++) {
			for (int j = 0; j < Ttransp.length; j++) {
				Ttransp[j][i] = T[i][j];
			}
		}
	}

	public void iteration(int minRow, int maxRow)
	{
		double localMaxRem = 0;
		for (int i = minRow; i < maxRow; i++) {
			double rowSum = 0;
			double[] row = T[i];
			double[] tmpRow = tmp[i];
			for (int j = 0; j < row.length; j++) {
				double[] col = Ttransp[j];
				double sum = 0;
				for (int k = 0; k < T.length; k++)
					sum += row[k] * col[k];
				if (j < T.length)
					rowSum += sum;
				if (j >= T.length)
					sum += row[j];
				tmpRow[j] = sum;
			}
			localMaxRem = Double.max(localMaxRem, rowSum);
		}
		maxRem.accumulate(localMaxRem);
	}
}
