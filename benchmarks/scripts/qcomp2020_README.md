QComp Benchmarking Scripts
==============

This is a collection of simple scripts, initially written for QComp2019 by Tim Quatmann <tim.quatmann@cs.rwth-aachen.de>, used to execute the benchmarks from the QComp website.
For bug reports and other kinds of feedback, please contact Michaela Klauck <klauck@depend.uni-saarland.de>, who adapted the scripts for the 2020 edition of QComp.


#Requirements

The scripts only require Python 3.


#Terminology

- Benchmark: A benchmark is a combination of a model, a parameter assignment, and a single property.
  Each benchmark has a unique identifier (e.g. `consensus.4-2.disagree`) consisting of the short model name,
  the parameter values (in the order they appear on the website), and the name of the property.
- Invocation: An invocation is a sequence of command lines that produce the result of a single given benchmark by invoking the tool.
  We consider *sequences* of command lines to allow for, e.g., conversions to other formalisms before executing the actual tool.
- Execution: An execution is the result of executing an invocation.


# Overview of Files

- `benchmark.py` provides access to the data associated with a benchmark (including the meta data as stored on qcomp.org).
- `execute_invocations.py` executes a list of invocations (see below).
- `execution.py` provides access to the data associated with an execution. 
- `generate_benchmark_list.py` generates a list of benchmarks (see below).
- `generate_invocations.py` generates a list of invocations (see below).
- `generate_result_table.py` generates a table from the execution data (see below). 
- `invocation.py` provides access to the data associated with an invocation.
- `qcomp_2020_benchmarks.csv` contains the benchmarks for QCOMP 2020
- `qcomp_2020_generate_invocations.py` generates a list of invocations for the QCOMP 2020 benchmarks.
- `tool.py` implements methods specific for the tool that is to be benchmarked.
   Users should replace the example implementations in this file with their own one. 
- `utility.py` provides various utility functions.

Moreover, a file `settings.json` is created in the current working directory, whenever one of the python scripts is executed.
This file can be edited to adapt certain settings of the benchmark scripts.


# Selecting a Benchmark List

The first step is to select benchmarks that should be run. This is done by implementing the method `is_on_benchmark_list` in the file `tool.py`.
This method should return True if and only if the provided benchmark should be on the list.
Afterwards, execute
```commandline
python3 /path/to/qcomp/scripts/generate_benchmark_list.py
```
This will create a .csv file that lists all selected benchmarks.


# Creating a List of Invocations

After specifying a set of benchmarks, we need to create tool invocations for them.
This requires the method `get_invocations` in the file `tool.py` to return a list of `Invocation` objects for the given benchmark.
Each invocation consists of

 * the benchmark identifier (that should be unique among all returned invocations for the given benchmark)
 * the invocation identifier, `default` or `specific`
 * a track identifier, specifying to which of the five tracks in QComp 2020 the call belongs (`correct`, `probably-epsilon-correct`, `often-epsilon-correct`, `often-epsilon-correct-10-min`)
 * a sequence of command lines that invoke the tool as well as potential pre- or postprocessing steps such as conversion to a compatible modeling language.

For the command lines, it can be assumed that all files related to the model (i.e., the .jani file and the file(s) of the original formalism) are copied
to the current working directory before executing the command.

If the benchmark is not supported, the returned list should be empty.
For **QComp 2020** up to two invocations per benchmark and track are allowed.
However, the first invocation should call the tool with its default settings while the second one can also set benchmark-specific options, e.g., the fastest engine for this benchmark. For tweaking the parameters only the usage of the information about the type of the model, the type of the property and the expected size of the state space is allowed.

After implementing `get_invocations`, run
```commandline
python3 /path/to/qcomp/scripts/generate_invocations.py
```
to create a .json file containing the invocations for all selected benchmarks.
Alternatively, you can run `qcomp_2020_generate_invocations.py` instead, to generate invocations for the QCOMP 2020 benchmarks.


# Executing the Commands

To execute all invocations, run
```commandline
python3 /path/to/qcomp/scripts/execute_invocations.py
```
This will run all specified commands, measures their time, saves the output, and compares the produced results with the reference results from qcomp.org.
For this to work, the method `get_result` in `tool.py` needs to extract the result for the given benchmark from the given execution.
There are (at least) two ways to achieve this:

- Find the result in the output of the tool (the output of each command line is stored in `execution.logs`)
- Read the result from a file that the tool has produced (the method is called exactly once, right after executing the command lines for the given invocation)

The returned result should be either ``"true"``, ``"false"``, a decimal number, or a fraction.

See also the file `settings.json` (which will be created in the current working directory) to adapt the time-limit, goal precision, etc.

The script will store a logfile for each invocation as well as a .json file containing the gathered data.


# Creating a Table of Results

To create a .csv file that summarizes the execution results, simply run
```commandline
python3 /path/to/qcomp/scripts/generate_result_table.py
```
