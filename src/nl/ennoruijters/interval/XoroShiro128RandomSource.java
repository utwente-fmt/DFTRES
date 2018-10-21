package nl.ennoruijters.interval;

import java.util.Random;
import java.security.SecureRandom;

public class XoroShiro128RandomSource extends Random implements RandomSource
{
	long state[] = new long[2];

	public XoroShiro128RandomSource()
	{
		byte[] seed = SecureRandom.getSeed(16);
		for (int i = 0; i < 8; i++)
			state[0] = (state[0] << 8) | (seed[i] & 0xff);
		for (int i = 0; i < 8; i++)
			state[1] = (state[1] << 8) | (seed[i + 8] & 0xff);
	}

	public XoroShiro128RandomSource(long seed)
	{
		state[0] = seed ^ 0x5555555555555555L;
		state[1] = seed * 0xCCCCCCCCCCCCCCCCL;
		for (int i = 0; i < 1000; i++)
			randInt();
	}

	public int randInt()
	{
		long ret = state[0] + state[1];
		int iret;
		state[1] ^= state[0];
		state[0] = ((state[0] << 55) | (state[0] >>> 9));
		state[0] ^= state[1] ^ (state[1] << 14);
		state[1] = (state[1] << 36) | (state[1] >>> 28);
		iret = (int)ret;
		iret ^= ret >>> 48;
		iret ^= (ret >>> 16) & 0xFFFF0000;
		return iret;
	}

	public long randLong()
	{
		long ret = state[0] + state[1];
		state[1] ^= state[0];
		state[0] = ((state[0] << 55) | (state[0] >>> 9));
		state[0] ^= state[1] ^ (state[1] << 14);
		state[1] = (state[1] << 36) | (state[1] >>> 28);
		return ret;
	}

	protected int next(int bits)
	{
		return randInt() >>> (32 - bits);
	}

	public double nextDouble()
	{
		long s0 = state[0], s1 = state[1];
		long ret = s0 + s1;
		int iret;
		s1 ^= s0;
		s0 = ((s0 << 55) | (s0 >>> 9));
		state[0] = s0 ^ s1 ^ (s1 << 14);
		state[1] = (s1 << 36) | (s1 >>> 28);
		ret &= Long.MAX_VALUE;
		return ret * (0.5 / (1L << 62));
	}
}
