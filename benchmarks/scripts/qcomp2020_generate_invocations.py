from benchmark import *
from utility import *
from invocation import Invocation
import tool

"""
This script creates a list of invocations for the QCOMP 2020 benchmarks.
The invocations can be executed via 'execute_invocations.py'
"""

qcomp_benchmarks = []

if __name__ == "__main__":
    benchmarks = load_csv(os.path.join(sys.path[0], "qcomp2020_benchmarks.csv"))
    progressbar = Progressbar(len(benchmarks), "Generating invocations for benchmarks")
    invocations = []
    num_b = 0
    for benchmark_csv in benchmarks:
        b = get_benchmark_from_id("{}.{}.{}".format(benchmark_csv[0], benchmark_csv[3], benchmark_csv[4]))
        num_b = num_b + 1
        progressbar.print_progress(num_b)
        b.check_validity()
        invocations_b = tool.get_invocations(b)
        if invocations_b is not None:
            if isinstance(invocations_b, Invocation):
                invocations_b = [invocations_b]
            # if len(invocations_b) > 2:
            #     print("Found more than two invocations for benchmark {}. This is not allowed in QCOMP2019.".format(b.get_identifier()))
            for i in invocations_b:
                i_json = OrderedDict([("benchmark-id", b.get_identifier())])
                i_json.update(i.to_json())
                invocations.append(i_json)
    save_json(invocations, settings.invocations_filename())
    print("\nSaved {} invocations to file '{}'".format(len(invocations), settings.invocations_filename()))