import tool
from benchmark import *
from time import sleep
from invocation import *
from utility import *
from shutil import copyfile
import traceback

if __name__ == "__main__":
    ensure_directory(settings.logs_dir())

    for dir in settings.clean_up_dirs():
        ensure_directory(dir)

    if len(settings.clean_up_dirs()) > 0:
        print("WARNING: This script potentially removes files in the following directories:\n\t" + "\n\t".join(settings.clean_up_dirs()) + "\nMake sure that these directories do not contain important data.")
        input("Press Enter to continue...")

    # Do some sanity checks
    if not is_valid_filename(settings.results_filename()):
        raise AssertionError("Unable to write to result file {}".format(settings.results_filename()))
    loaded_invocations = load_json(settings.invocations_filename())
    invocation_number = 0
    progressbar = Progressbar(len(loaded_invocations), "Checking input")
    invocations = []
    benchmark_to_invocations = dict()
    qcomp_benchmarks = load_csv(os.path.join(sys.path[0], "qcomp2020_benchmarks.csv"))
    for invocation_json in loaded_invocations:
        invocation_number = invocation_number + 1
        progressbar.print_progress(invocation_number)
        try:
            # check whether there are no commands
            if not "commands" in invocation_json or len(invocation_json["commands"]) == 0 or (len(invocation_json["commands"]) == 1 and invocation_json["commands"][0] == ""):
                continue
            benchmark_id = invocation_json["benchmark-id"]
            benchmark = get_benchmark_from_id(benchmark_id)
            #ensure that this benchmark is actually on the list of selected benchmarks.
            on_list = False
            for qcomp_benchmark in qcomp_benchmarks:
                if benchmark_id == "{}.{}.{}".format(qcomp_benchmark[0], qcomp_benchmark[3], qcomp_benchmark[4]):
                    on_list = True
                    break
            if not on_list:
                raise AssertionError("Benchmark with identifier {} is not part of QComp 2020".format(benchmark_id))
            # ensure that no files in the current directory will be overwritten and that the actual benchmark files exist
            for filename in benchmark.get_all_filenames():
                if not os.path.isfile(os.path.join(benchmark.get_directory(), filename)):
                    raise AssertionError(
                        "The file '{}' does not exist.".format(
                            os.path.join(benchmark.get_directory(), filename)))
                if os.path.isfile(os.path.join(os.path.curdir, filename)):
                    raise AssertionError("The file '{}' would be overwritten since one of the benchmark files has the same name.".format(os.path.join(os.path.curdir, filename)))
            # ensure that the invocation id can be part of a filename and is unique
            invocation = Invocation(invocation_json)
            if not is_valid_filename(invocation.identifier, "./"):
                raise AssertionError("Invocation identifier '{}' is either not a valid filename or contains a '.'.".format(invocation.identifier))
            if not benchmark_id in benchmark_to_invocations:
                benchmark_to_invocations[benchmark_id] = set()
            # if invocation.identifier in benchmark_to_invocations[benchmark_id]:
            #     raise AssertionError("Invocation identifier '{}' already exists for benchmark '{}'.".format(invocation.identifier, benchmark_id))
            benchmark_to_invocations[benchmark_id].add(invocation.identifier)
            # if len(benchmark_to_invocations[benchmark_id]) > 2:
            #     raise AssertionError("Found more than two invocations for benchmark {}.".format(benchmark_id))
            invocations.append(invocation_json)
        except Exception:
            if "benchmark-id" in invocation_json:
                raise AssertionError("Error when checking invocation #{}: {}".format(invocation_number, invocation_json["benchmark-id"]))
            else:
                raise  AssertionError("Error when checking invocation #{}".format(invocation_number))

    for bench_id in benchmark_to_invocations:
        if not "default" in benchmark_to_invocations[bench_id]:
            raise AssertionError("No default invocation for {}. Only found invocations: {}.".format(bench_id, benchmark_to_invocations[bench_id]))
        for inv_id in benchmark_to_invocations[bench_id]:
            if inv_id not in ["default", "specific"]:
                raise AssertionError("Invalid invocation identifier {} for benchmark {}.".format(inv_id, bench_id))
    print("")

    # Then invoke the benchmarks
    progressbar = Progressbar(len(invocations), "Executing invocations")
    invocation_number = 0
    tool_results = []
    for invocation_json in invocations:
        invocation_number = invocation_number + 1
        progressbar.print_progress(invocation_number)
        benchmark = get_benchmark_from_id(invocation_json["benchmark-id"])
        invocation = Invocation(invocation_json)
        # save the contents of the directories that are to be cleaned up.
        # All files that are created during the execution will be deleted later.
        old_dir_contents = []
        for dir in settings.clean_up_dirs():
            old_dir_contents.append(os.listdir(dir))
        try:
            # copy all referenced files into the current directory
            for filename in benchmark.get_all_filenames():
                copyfile(os.path.join(benchmark.get_directory(), filename), os.path.join(os.path.curdir, filename))
            # execute the invocation
            notes = []
            execution = invocation.execute()
            tool_result = OrderedDict(invocation_json)
            tool_result["wallclock-time"] = execution.wall_time
            tool_result["timeout"] = execution.timeout
            tool_result["execution-error"] = execution.error
            try:
                result = tool.get_result(benchmark, execution)
            except Exception:
                print("ERROR while getting result for invocation #{}: {}/{}".format(invocation_number,
                                                                            invocation_json["benchmark-id"],
                                                                            invocation_json["invocation-id"],
                                                                            invocation_json["invocation-track-id"]))
                result = None
            if result is not None:
                tool_result["result"] = str(result)  # convert to str to not lose precision
                result = try_to_bool_or_number(result)
                if benchmark.has_reference_result():
                    tool_result["result-correct"] = is_result_correct(benchmark.get_reference_result(), result, invocation.track_id)
                    if is_number(result) and is_number_or_interval(benchmark.get_reference_result()):
                        tool_result["absolute-error"] = try_to_float(get_absolute_error(benchmark.get_reference_result(), result))
                        tool_result["relative-error"] = try_to_float(get_relative_error(benchmark.get_reference_result(), result))
                        if not tool_result["result-correct"]:
                            # Prepare a message
                            if settings.is_relative_precision():
                                error_kind = "a relative"
                                error_value = tool_result["relative-error"]
                            else:
                                error_kind = "an absolute"
                                error_value = tool_result["absolute-error"]
                            notes.append("The tool result '{}' is tagged as incorrect. The reference result is '{}' (approx. {}) which means {} error of '{}' which is larger than the goal precision '{}'.".format(result, benchmark.get_reference_result(), try_to_float(benchmark.get_reference_result()), error_kind, error_value, try_to_float(settings.goal_precision())))
                    elif not tool_result["result-correct"]:
                        notes.append("Result '{}' is tagged as incorrect because it is different from the reference result '{}'.".format(result, benchmark.get_reference_result()))
                else:
                    notes.append("Correctness of result is not checked because no reference result is available.")
            elif not execution.timeout and not execution.error:
                notes.append("Unable to obtain tool result.")
            tool_result["notes"] = notes

            logfile_name = tool.get_name() + "." + invocation.identifier + "." + benchmark.get_identifier() + "." + invocation.track_id + ".log"
            tool_result["log"] = logfile_name
            with open(os.path.join(settings.logs_dir(), logfile_name), 'w', encoding="utf-8") as logfile:
                logfile.write(execution.concatenate_logs())
                if len(notes) > 0:
                    logfile.write("\n" + "#"*30 + " Notes " + "#"*30 + "\n")
                for note in notes:
                    logfile.write(note + "\n")
            tool_results.append(tool_result)
        except KeyboardInterrupt as e:
            print("\nInterrupt while processing invocation #{}: {}/{}".format(invocation_number, invocation_json["benchmark-id"], invocation_json["invocation-track-id"], invocation_json["invocation-id"]))
            break
        except Exception:
            print("ERROR while processing invocation #{}: {}/{}".format(invocation_number, invocation_json["benchmark-id"], invocation_json["invocation-track-id"], invocation_json["invocation-id"]))
            traceback.print_exc()
        finally:
            # remove files that were created during the execution
            for dir, old_contents in zip(settings.clean_up_dirs(), old_dir_contents):
                remove_directory_contents(dir, old_contents)


    save_json(tool_results, settings.results_filename())
    print("\nSaved {} tool execution results to file '{}'".format(len(tool_results), settings.results_filename()))
