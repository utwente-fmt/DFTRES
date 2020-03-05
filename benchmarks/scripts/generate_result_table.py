from benchmark import *
from utility import *


if __name__ == "__main__":
    tool_results = load_json(settings.results_filename())
    table = []
    id_to_row = dict()

    progressbar = Progressbar(len(tool_results), "Processing tool results")
    num_r = 0
    for r in tool_results:
        num_r = num_r + 1
        progressbar.print_progress(num_r)
        b_id = r["benchmark-id"]
        if not b_id in id_to_row:
            id_to_row[b_id] = len(table)
            b = get_benchmark_from_id(r["benchmark-id"])
            b.check_validity()
            table.append([b.get_model_short_name(), b.get_model_type().upper(), b.get_parameter_values_string(), b.get_property_name(), b.get_short_property_type()])
        table_row = table[id_to_row[b_id]]
        table_row.append(r["invocation-id"])
        table_row.append(r["wallclock-time"])
        notes = []
        if r["timeout"]:
            notes.append("Timeout")
        if r["execution-error"]:
            notes.append("Error")
        if not r["timeout"] and not r["execution-error"] and not "result" in r:
            notes.append("Unable to extract tool result")
        if "result-correct" in r:
            if r["result-correct"]:
                notes.append("Result correct.")
            else:
                notes.append("Result incorrect.")
        if len(notes) > 0:
            note = ", ".join(notes)
        else:
            note = ""
        table_row.append(note)

    save_csv(table, "results.csv")

