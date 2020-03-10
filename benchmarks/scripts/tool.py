from benchmark import Benchmark
from invocation import Invocation
from execution import Execution



ONLY_QCOMP_2020_BENCHMARKS = False
QComp2020_benchmarks = []
#
if ONLY_QCOMP_2020_BENCHMARKS and len(QComp2020_benchmarks) == 0:
	from utility import load_csv
	print("\n\nNOTE: Will filter on the 100 benchmarks selected for QComp 2020\n")
	QComp2020_benchmarks = [ b[0]+"."+b[3] +"."+b[4] for b in load_csv("qcomp2020_benchmarks.csv") ]
	assert(len(QComp2020_benchmarks) == 100)


def get_name():
	"""
	Returns the name of the tool as listed on http://qcomp.org/competition/2020/
	"""
	return "DFTRES"  # https://doi.org/10.1016/j.ress.2019.02.004


def is_benchmark_supported(benchmark : Benchmark):
	"""
	Returns True if the provided benchmark is supported by the tool and
	if the given benchmark should appear on the generated benchmark list
	"""
	# DFTRES only supports Markovian models with purely spurious nondeterminism
	if not(benchmark.is_dtmc() or benchmark.is_ctmc() or benchmark.is_ma()):
		return False
	# User-defined functions (the "call" JANI operator) are not supported
	if "functions" in benchmark.get_jani_features():
		return False
	# Only time-accumulating or time-instant reward queries
	supported_queries = [
		"prob-reach",
		"prob-reach-time-bounded",
		"steady-state-prob"
	]
	if not benchmark.get_property_type() in supported_queries:
		return False
	# No support for real variables yet
	real_vars = [ v for v in benchmark.load_jani_file()["variables"] \
					 if v["type"] == "real"]
	if 0 < len(real_vars):
		return False
	# Some MAs have not-obviously-spurious nondeterminism and can't be simulated
	unsupported_models = [
		"bitcoin-attack",
	]
	# The arithmetic operations of some models aren't supported
	unsupported_models += [
		"majority",
		"philosophers",
		"speed-ind",
		"dpm",
		"readers-writers"
	]
	if benchmark.get_model_short_name() in unsupported_models:
		return False
	# All other models are supported
	if ONLY_QCOMP_2020_BENCHMARKS:
		return benchmark.get_identifier() in QComp2020_benchmarks
	else:
		return True


def get_invocations(benchmark : Benchmark):
	"""
	Returns a list of invocations that invoke the tool for the given benchmark.
	It can be assumed that the current directory is the directory from which
	execute_invocations.py is executed.

	For QCOMP 2020, this should return a list of invocations for all tracks
	in which the tool can take part. For each track an invocation
	with default settings has to be provided and in addition, an optimized
	setting (e.g., the fastest engine and/or solution technique for this
	benchmark) can be specified.  Only information about the model type,
	the property type and the state space size are allowed to be used to
	tweak the parameters.
   
	If this benchmark is not supported, an empty list has to be returned.
	"""
	#
	if not is_benchmark_supported(benchmark):
		return []
	#
	OUR_TRACKS = {
		# track-id : relative error
		"probably-epsilon-correct"    : 5e-2,
		"often-epsilon-correct"       : 1e-3,
		"often-epsilon-correct-10-min": 1e-99,  # run till judgement day
	}
	TWEAKS = "--unsafe-scheduling --fixed-batches"
	JVM = "java -Xmx6G -XX:+UseParallelGC"
	RNG = "-s 0"  # seed 0 for reproducibility
	PROP = "--prop " + benchmark.get_property_name()
	PARAMS = ""
	for p in benchmark.get_parameters():
		PARAMS += " --def " + p["name"] + " " + str(p["value"])
	CALL = " ".join([JVM, "-jar DFTRES/DFTRES.jar", RNG, PROP, PARAMS])
	invocations = []
	for track,precision in OUR_TRACKS.items():
		err  = "--relErr " + str(precision)
		for invID,tweaks in [("default",""), ("specific",TWEAKS)]:
			i = Invocation()
			i.identifier = invID
			i.track_id = track
			i.add_command(" ".join([CALL,err,tweaks,benchmark.get_janifilename()]))
			invocations.append(i)
	return invocations


def get_result(benchmark : Benchmark, execution : Execution):
	"""
	Returns the result of the given execution on the given benchmark.
	This method is called after executing the commands of the associated invocation.
	One can either find the result in the tooloutput (as done here) or
	read the result from a file that the tool has produced.
	The returned value should be either 'true', 'false', a decimal number, or a fraction.
	"""
	invocation = execution.invocation
	log = execution.concatenate_logs()
	RESULT_MARKER = "point estimate: "
	pos = log.find(RESULT_MARKER)
	if pos < 0:
		return None
	pos = pos + len(RESULT_MARKER)
	eol_pos = log.find(",", pos)
	return log[pos:eol_pos]
