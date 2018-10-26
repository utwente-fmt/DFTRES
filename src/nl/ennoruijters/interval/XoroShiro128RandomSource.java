package nl.ennoruijters.interval;

import java.util.Random;
import java.security.SecureRandom;

public class XoroShiro128RandomSource extends Random implements RandomSource
{
	long s0, s1;

	public XoroShiro128RandomSource()
	{
		byte[] seed = SecureRandom.getSeed(16);
		for (int i = 0; i < 8; i++)
			s0 = (s0 << 8) | (seed[i] & 0xff);
		for (int i = 0; i < 8; i++)
			s1 = (s1 << 8) | (seed[i + 8] & 0xff);
	}

	public XoroShiro128RandomSource(long seed)
	{
		setSeed(seed);
	}

	public void setSeed(long seed)
	{
		/* SplitMix64 generator to fill the initial state */
		long z = (seed += 0x9e3779b97f4a7c15L);
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		s0 = z ^ (z >>> 31);
		z = (seed += 0x9e3779b97f4a7c15L);
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		s1 = z ^ (z >>> 31);
	}

	public int randInt()
	{
		long ret = s0 + s1;
		int iret;
		s1 ^= s0;
		s0 = ((s0 << 55) | (s0 >>> 9));
		s0 ^= s1 ^ (s1 << 14);
		s1 = (s1 << 36) | (s1 >>> 28);
		iret = (int)ret;
		iret ^= ret >>> 48;
		iret ^= (ret >>> 16) & 0xFFFF0000;
		return iret;
	}

	public long randLong()
	{
		long ret = s0 + s1;
		s1 ^= s0;
		s0 = ((s0 << 55) | (s0 >>> 9));
		s0 ^= s1 ^ (s1 << 14);
		s1 = (s1 << 36) | (s1 >>> 28);
		return ret;
	}

	protected int next(int bits)
	{
		return randInt() >>> (32 - bits);
	}

	public double nextDouble()
	{
		long ret = s0 + s1;
		int iret;
		s1 ^= s0;
		s0 = ((s0 << 55) | (s0 >>> 9));
		s0 = s0 ^ s1 ^ (s1 << 14);
		s1 = (s1 << 36) | (s1 >>> 28);
		ret &= Long.MAX_VALUE;
		return ret * (0.5 / (1L << 62));
	}

	private int jumps;
	private XoroShiro128RandomSource(long seed0, long seed1, int jumps)
	{
		s0 = seed0;
		s1 = seed1;
		this.jumps = jumps;
	}

	private static final long LONG_JUMP_1 = 0xd2a98b26625eee7bL;
	private static final long LONG_JUMP_2 = 0xdddf9b1090aa7ac1L;
	public XoroShiro128RandomSource long_jump()
	{
		if (jumps == Integer.MAX_VALUE)
			throw new UnsupportedOperationException("Can only sub-RNG once.");
		XoroShiro128RandomSource ret;
		ret = new XoroShiro128RandomSource(s0, s1, Integer.MAX_VALUE);
		jumps++;
		/* This is the long-jump function for the generator. It
		 * is equivalent to 2^96 calls to randInt(); it can be
		 * used to generate 2^32 non-overlapping subsequences
		 * for parallel computations.
		 */
		long t0 = 0;
		long t1 = 0;
		for(int b = 0; b < 64; b++) {
			if ((LONG_JUMP_1 & (1L << b)) != 0) {
				t0 ^= s0;
				t1 ^= s1;
			}
			randLong();
		}
		for(int b = 0; b < 64; b++) {
			if ((LONG_JUMP_2 & (1L << b)) != 0) {
				t0 ^= s0;
				t1 ^= s1;
			}
			randLong();
		}
		s0 = t0;
		s1 = t1;
		return ret;
	}
}
