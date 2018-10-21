package schemes;

import algorithms.ModelGenerator;
import algorithms.Scheme;
import java.util.Random;

// naive Monte Carlo scheme

public class SchemeMC extends Scheme {
	
	public SchemeMC(Random rng, ModelGenerator gen) {
		super(rng, gen);
		name = "standard Monte Carlo";
	}
}
