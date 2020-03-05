from utility import *

class Benchmark(object):
    """ This class represents a benchmark, that is
        * a model
        * an instantiation of file and open parameters
        * a single property
    """
    def __init__(self, index_json, model_file_index, open_parameter_index, property_index):
        """
        :param index_json: The json structure of the 'index.json' file of the model
        :param model_file_index: The index of the model file entry within the "files" entry in the json structure
        :param open_parameter_index: The index of the open parameter instantiation within the "open-parameter-values" entry of the file entry
        :param property_index: The index of the property within the "properties" entry of the json structure
        """
        self.index_json = index_json
        self.model_file_index = model_file_index
        self.open_parameter_index = open_parameter_index
        self.property_index = property_index

    def get_file_parameters(self):
        file_json = self.index_json["files"][self.model_file_index]
        if "file-parameter-values" in file_json:
            return file_json["file-parameter-values"]
        return []

    def get_open_parameters(self):
        file_json = self.index_json["files"][self.model_file_index]
        if "open-parameter-values" in file_json:
            open_par_json = file_json["open-parameter-values"]
            if len(open_par_json) > 0 and "values" in open_par_json[self.open_parameter_index]:
                return open_par_json[self.open_parameter_index]["values"]
        return []

    def get_parameters(self):
        result = []
        unsorted_parameters = self.get_file_parameters() + self.get_open_parameters()
        for p in self.index_json["parameters"]:
            pname = p["name"]
            for other_p in unsorted_parameters:
                if other_p["name"] == pname:
                    result.append(other_p)
                    break
        return result

    def get_open_parameter_def_string(self):
        """ Returns the definition of the open model parameters in the form 'N=2,K=5,p=0.3'. The returned string is empty if there are no parameters. """
        par_def_string = ""
        pars = self.get_open_parameters()
        for p in pars:
            if len(par_def_string) > 0:
                par_def_string = par_def_string + ","
            value_str = str(p["value"]).lower() if type(p["value"]) is bool else str(p["value"])
            par_def_string = par_def_string + "{}={}".format(p["name"], value_str)
        return par_def_string

    def get_parameter_values_string(self):
        par_val_string = ""
        pars = self.get_parameters()
        for p in pars:
            if len(par_val_string) > 0:
                par_val_string = par_val_string + "-"
            value_str = str(p["value"]).lower() if type(p["value"]) is bool else str(p["value"])
            par_val_string = par_val_string + value_str
        return par_val_string

    def get_property_name(self):
        return self.index_json["properties"][self.property_index]["name"]

    def get_model_short_name(self):
        return self.index_json["short"]

    def get_identifier(self):
        return "{}.{}.{}".format(self.get_model_short_name(), self.get_parameter_values_string(), self.get_property_name())

    def get_model_type(self):
        return self.index_json["type"]

    def is_ctmc(self):
        return self.get_model_type() == "ctmc"

    def is_dtmc(self):
        return self.get_model_type() == "dtmc"

    def is_mdp(self):
        return self.get_model_type() == "mdp"

    def is_ma(self):
        return self.get_model_type() == "ma"

    def is_pta(self):
        return self.get_model_type() == "pta"

    def get_original_format(self):
        return self.index_json["original"]

    def is_galileo(self):
        return self.get_original_format() == "Galileo"

    def is_greatspn(self):
        return self.get_original_format() == "GreatSPN"

    def is_modest(self):
        return self.get_original_format() == "Modest"

    def is_ppddl(self):
        return self.get_original_format() == "PPDDL"

    def is_pgcl(self):
        return self.get_original_format() == "PGCL"

    def is_prism(self):
        return self.get_original_format() == "PRISM"

    def is_prism_ma(self):
        return self.get_original_format() == "PRISM-MA"

    def is_prism_inf(self):
        return self.get_original_format() == "PRISM-∞"

    def get_property_type(self):
        return self.index_json["properties"][self.property_index]["type"]

    def get_short_property_type(self):
        t = self.get_property_type()
        if t == "prob-reach": return "P"
        elif t == "prob-reach-step-bounded": return "Pb"
        elif t == "prob-reach-time-bounded": return "Pb"
        elif t == "prob-reach-reward-bounded": return "Pb"
        elif t == "exp-steps": return "E"
        elif t == "exp-steps-step-bounded": return "Eb"
        elif t == "exp-steps-time-bounded": return "Eb"
        elif t == "exp-steps-reward-bounded": return "Eb"
        elif t == "exp-time": return "E"
        elif t == "exp-time-step-bounded": return "Eb"
        elif t == "exp-time-time-bounded": return "Eb"
        elif t == "exp-time-reward-bounded": return "Eb"
        elif t == "exp-reward": return "E"
        elif t == "exp-reward-step-bounded": return "Eb"
        elif t == "exp-reward-time-bounded": return "Eb"
        elif t == "exp-reward-reward-bounded": return "Eb"
        elif t == "exp-reward-step-instant": return "Ei"
        elif t == "exp-reward-time-instant": return "Ei"
        elif t == "exp-reward-reward-instant": return "Ei"
        elif t == "steady-state-prob": return "S"
        elif t == "steady-state-reward": return "S"
        else:
            raise AssertionError("Unhandled type {}".format(t))

    def is_unbounded_probabilistic_reachability(self):
        return self.get_property_type() == "prob-reach"

    def is_step_bounded_probabilistic_reachability(self):
        return self.get_property_type() == "prob-reach-step-bounded"

    def is_time_bounded_probabilistic_reachability(self):
        return self.get_property_type() == "prob-reach-time-bounded"

    def is_reward_bounded_probabilistic_reachability(self):
        return self.get_property_type() == "prob-reach-reward-bounded"

    def is_unbounded_expected_steps(self):
        return self.get_property_type() == "exp-steps"

    def is_step_bounded_expected_steps(self):
        return self.get_property_type() == "exp-steps-step-bounded"

    def is_time_bounded_expected_steps(self):
        return self.get_property_type() == "exp-steps-time-bounded"

    def is_reward_bounded_expected_steps(self):
        return self.get_property_type() == "exp-steps-reward-bounded"

    def is_unbounded_expected_time(self):
        return self.get_property_type() == "exp-time"

    def is_step_bounded_expected_time(self):
        return self.get_property_type() == "exp-time-step-bounded"

    def is_time_bounded_expected_time(self):
        return self.get_property_type() == "exp-time-time-bounded"

    def is_reward_bounded_expected_time(self):
        return self.get_property_type() == "exp-time-reward-bounded"

    def is_unbounded_expected_reward(self):
        return self.get_property_type() == "exp-reward"

    def is_step_bounded_expected_reward(self):
        return self.get_property_type() == "exp-reward-step-bounded"

    def is_time_bounded_expected_reward(self):
        return self.get_property_type() == "exp-reward-time-bounded"

    def is_reward_bounded_expected_reward(self):
        return self.get_property_type() == "exp-reward-reward-bounded"

    def is_step_instantaneous_expected_reward(self):
        return self.get_property_type() == "exp-reward-step-instant"

    def is_time_instantaneous_expected_reward(self):
        return self.get_property_type() == "exp-reward-time-instant"

    def is_reward_instantaneous_expected_reward(self):
        return self.get_property_type() == "exp-reward-reward-instant"

    def is_steady_state_reward(self):
        return self.get_property_type() == "steady-state-reward"

    def is_steady_state_probability(self):
        return self.get_property_type() == "steady-state-prob"

    def get_directory(self):
        return os.path.realpath(os.path.join(settings.benchmark_dir(), "{}/{}".format(self.get_model_type(), self.get_model_short_name())))

    def get_janifilename(self):
        return self.index_json["files"][self.model_file_index]["file"]

    def get_original_filenames(self):
        result = self.index_json["files"][self.model_file_index]["original-file"]
        if isinstance(result, str):
            result = [result]
        return result

    def get_all_filenames(self):
        return self.get_original_filenames() + [self.get_janifilename()]

    def get_galileo_filename(self):
        if not self.is_galileo():
            raise AssertionError("Invalid operation: Not a Gallileo model.")
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".dft"]:
                return f
        raise AssertionError("Unable to find galileo file.")

    def get_greatspn_model_filename(self):
        if not self.is_greatspn():
            raise AssertionError("Invalid operation: Not a GreatSPN model.")
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".pnpro"]:
                return f
        raise AssertionError("Unable to find GreatSPN model file.")

    def get_greatspn_propery_filename(self):
        if not self.is_greatspn():
            raise AssertionError("Invalid operation: Not a GreatSPN model.")
        has_cap_file = False
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".csl", ".props"]:
                return f
            if os.path.splitext(f)[1].lower() in [".capacities"]:
                has_cap_file = True

        # there might be no properties file
        if len(self.get_original_filenames()) == 1 or (len(self.get_original_filenames()) == 2 and has_cap_file):
            return ""
        raise AssertionError("Unable to find GreatSPN property file.")

    def get_greatspn_capacities_filename(self):
        if not self.is_greatspn():
            raise AssertionError("Invalid operation: Not a GreatSPN model.")
        has_prop_file = False
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".capacities"]:
                return f
            if os.path.splitext(f)[1].lower() in [".csl", ".props"]:
                has_prop_file = True
        # there might be no capacities file
        if len(self.get_original_filenames()) == 1 or (len(self.get_original_filenames()) == 2 and has_prop_file):
            return ""
        raise AssertionError("Unable to find GreatSPN capacities file.")

    def get_modest_filename(self):
        if not self.is_modest():
            raise AssertionError("Invalid operation: Not a Modest model.")
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".modest"]:
                return f
        raise AssertionError("Unable to find Modest file.")

    def get_ppddl_domain_filename(self):
        if not self.is_ppddl():
            raise AssertionError("Invalid operation: Not a ppddl model.")
        if len(self.get_original_filenames()) == 2:
            for i in [0,1]:
                if "domain" in self.get_original_filenames()[i].lower():
                    return self.get_original_filenames()[i]
                elif "problem" in self.get_original_filenames()[i].lower():
                    return self.get_original_filenames()[1-i]
        raise AssertionError("Unable to find PPDDL domain file.")

    def get_ppddl_problem_filename(self):
        if not self.is_ppddl():
            raise AssertionError("Invalid operation: Not a ppddl model.")
        if len(self.get_original_filenames()) == 2:
            for i in [0,1]:
                if "domain" in self.get_original_filenames()[i].lower():
                    return self.get_original_filenames()[1-i]
                elif "problem" in self.get_original_filenames()[i].lower():
                    return self.get_original_filenames()[i]
        raise AssertionError("Unable to find PPDDL problem file.")

    def get_pgcl_program_filename(self):
        if not self.is_pgcl():
            raise AssertionError("Invalid operation: Not a PGCL model.")
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".pgcl"]:
                return f
        raise AssertionError("Unable to find pgcl program file.")

    def get_pgcl_property_filename(self):
        if not self.is_pgcl():
            raise AssertionError("Invalid operation: Not a PGCL model.")
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".props"]:
                return f
        raise AssertionError("Unable to find pgcl property file.")

    def get_prism_program_filename(self):
        if not (self.is_prism() or self.is_prism_ma() or self.is_prism_inf()):
            raise AssertionError("Invalid operation: Not a Prism model.")
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".prism", ".nm", ".pm", ".sm", ".ma"]:
                return f
        raise AssertionError("Unable to find prism program file.")

    def get_prism_property_filename(self):
        if not (self.is_prism() or self.is_prism_ma() or self.is_prism_inf()):
            raise AssertionError("Invalid operation: Not a Prism model.")
        for f in self.get_original_filenames():
            if os.path.splitext(f)[1].lower() in [".props", ".pctl", ".csl", ".prctl", ".csrl"]:
                return f
        raise AssertionError("Unable to find prism property file.")

    def get_reference_result(self):
        file_json = self.index_json["files"][self.model_file_index]
        if "open-parameter-values" in file_json:
            open_par_json = file_json["open-parameter-values"]
            if len(open_par_json) > 0 and "results" in open_par_json[self.open_parameter_index]:
                ref_results = open_par_json[self.open_parameter_index]["results"]
                for r in ref_results:
                    if r["property"] == self.get_property_name():
                        if is_bool(r["value"]):
                            return bool(r["value"])
                        elif is_interval(r["value"]):
                            return r["value"]
                        else:
                            return try_to_number(r["value"])
        return None

    def has_reference_result(self):
        return self.get_reference_result() is not None

    def get_max_num_states(self):
        """ Returns the maximum number of states a tool produced for this benchmark (or None, if there is no states number)"""
        file_json = self.index_json["files"][self.model_file_index]
        if "open-parameter-values" in file_json:
            open_par_json = file_json["open-parameter-values"]
            if len(open_par_json) > 0 and "states" in open_par_json[self.open_parameter_index]:
                states = open_par_json[self.open_parameter_index]["states"]
                max_val = -1
                for s in states:
                    if s["number"] == "∞":
                        max_val = math.inf
                    else:
                        max_val = max(max_val, s["number"])
                if max_val >= 0:
                    return max_val
        return None


    def categorize(self, state_num):
        if state_num > 100000000:
            return 100000000
        else:
            if state_num >= 50000000:
                return 50000000
            else:
                if state_num >= 20000000:
                    return 20000000
                else:
                    if state_num >= 10000000:
                        return 10000000
                    else:
                        if state_num >= 5000000:
                            return 5000000
                        else:
                            if state_num >= 1000000:
                                return 1000000
                            else:
                                if state_num >= 500000:
                                    return 500000
                                else:
                                    if state_num >= 100000:
                                        return 100000
                                    else:
                                        return 999999


    def get_num_states_tweak(self):
        """ Returns the number of states Storm produced for this benchmark without cutting off after goal states, allowed for tweaking in QComp2020 (or None, if there is no states number)"""
        file_json = self.index_json["files"][self.model_file_index]
        if not self.is_pta():
            if "open-parameter-values" in file_json:
                open_par_json = file_json["open-parameter-values"]
                if len(open_par_json) > 0 and "states" in open_par_json[self.open_parameter_index]:
                    states = open_par_json[self.open_parameter_index]["states"]
                    state_num = None
                    for s in states:
                        if s["note"] == "Storm":
                            if s["number"] == "∞":
                                state_num = math.inf
                            else:
                                state_num = s["number"]
                    if states is not None and state_num is not None:
                        return self.categorize(state_num)           
        return None


    def load_jani_file(self):
        """ Returns the contens of the jani file """
        try:
            return load_json(os.path.join(self.get_directory(), self.get_janifilename()))
        except UnicodeDecodeError:
            print("ERROR: Unable to load jani file '{}'".format(os.path.join(self.get_directory(), self.get_janifilename())))
        return OrderedDict()

    def get_jani_features(self):
        """ Returns the list of jani features """
        model_jani = self.load_jani_file()
        if "features" in model_jani:
            return model_jani["features"]
        # the 'features' entry is optional.
        return []

    def check_validity(self):
        """ performs some simple sanity checks to find potential errors/inconsistencies within the meta data """
        copy = get_benchmark_from_id(self.get_identifier())
        if self.get_identifier() != copy.get_identifier():
            raise AssertionError("Identifier of copied benchmark missmatches: {} vs {}".format(self.get_identifier(), copy.get_identifier()))

        if not (self.is_ctmc() or self.is_dtmc() or self.is_ma() or self.is_mdp() or self.is_pta()):
            raise AssertionError("Unexpected model type: {} for {}".format(self.get_model_type(), self.get_identifier()))

        if self.is_galileo():
            self.get_galileo_filename()
        elif self.is_greatspn():
            self.get_greatspn_model_filename()
            self.get_greatspn_propery_filename()
            self.get_greatspn_capacities_filename()
        elif self.is_modest():
            self.get_modest_filename()
        elif self.is_ppddl():
            self.get_ppddl_domain_filename()
            self.get_ppddl_problem_filename()
        elif self.is_pgcl():
            self.get_pgcl_program_filename()
            self.get_pgcl_property_filename()
        elif self.is_prism() or self.is_prism_ma() or self.is_prism_inf():
            self.get_prism_program_filename()
            self.get_prism_property_filename()
        else:
            raise AssertionError("Unexpected original format: {} for {}".format(self.get_original_format(), self.get_identifier()))

        if not (self.is_unbounded_probabilistic_reachability() or self.is_step_bounded_probabilistic_reachability() or self.is_time_bounded_probabilistic_reachability() or self.is_reward_bounded_probabilistic_reachability() or self.is_unbounded_expected_steps() or self.is_step_bounded_expected_steps() or self.is_time_bounded_expected_steps() or self.is_reward_bounded_expected_steps() or self.is_unbounded_expected_time() or self.is_step_bounded_expected_time() or self.is_time_bounded_expected_time() or self.is_reward_bounded_expected_time() or self.is_unbounded_expected_reward() or self.is_step_bounded_expected_reward() or self.is_time_bounded_expected_reward() or self.is_reward_bounded_expected_reward() or self.is_step_instantaneous_expected_reward() or self.is_time_instantaneous_expected_reward() or self.is_reward_instantaneous_expected_reward() or self.is_steady_state_reward() or self.is_steady_state_probability()):
            raise AssertionError("Unexpected property type: {} for {}".format(self.get_property_type(), self.get_identifier()))
        self.get_short_property_type()

        for f in self.get_all_filenames():
            if not os.path.isfile(os.path.join(self.get_directory(), f)):
                raise AssertionError("Can not find file {}".format(os.path.join(self.get_directory(), f)))

        self.get_max_num_states()

def get_all_benchmarks(benchmark_directories):
    """ Creates a list of benchmark objects from the given benchmark directories. """
    dirname = os.path.curdir
    if os.path.isfile(benchmark_directories):
        dirname = os.path.dirname(benchmark_directories)
        benchmark_directories = load_json(benchmark_directories)

    benchmarks = []
    for p in benchmark_directories:
        # get the correct index.json file
        index_json_file = None
        if isinstance(p, str):
            index_json_file = p
        elif "path" in p:
            index_json_file = p["path"]
        index_json_file = os.path.join(os.path.join(dirname, index_json_file), "index.json")
        if not os.path.isfile(index_json_file):
            raise ValueError('Unknown index file for benchmark: {}'.format(index_json_file))
        index_json = load_json(index_json_file)
        # run over all available properties
        if not "properties" in index_json:
            raise ValueError('Can not find properties array in file ' + index_json_file + '.')
        for property_index in range(len(index_json["properties"])):
            # run over all available file indices
            if not "files" in index_json:
                raise ValueError('Can not find files array in file ' + index_json_file + '.')
            for model_file_index in range(len(index_json["files"])):
                file_json = index_json["files"][model_file_index]
                # run over all available open-parameter-values
                if not "open-parameter-values" in file_json or len(file_json["open-parameter-values"]) == 0:
                    open_parameter_indices = [0]
                else:
                    open_parameter_indices = range(len(file_json["open-parameter-values"]))
                for open_parameter_index in open_parameter_indices:
                    benchmarks.append(Benchmark(index_json, model_file_index, open_parameter_index, property_index))
    return benchmarks

def get_benchmark_from_id(id):
    """ Returns the benchmark object associated with the given identifier """
    id_info = id.split(".")
    short_name = id_info[0]
    parameter_definition_str = ".".join(id_info[1:-1])
    property_name = id_info[-1]

    # find the correct benchmark
    benchmark_directories = load_json(os.path.join(settings.benchmark_dir(), "index.json"))
    for p in benchmark_directories:
        model_path = os.path.join(settings.benchmark_dir(), p["path"])
        # get the correct index.json file
        if short_name == os.path.basename(model_path):
            model_index_json = load_json(os.path.join(model_path, "index.json"))
            # get the parameter_definition as a map
            parameter_definition = OrderedDict()
            if parameter_definition_str.strip() != "":
                parameter_definition = OrderedDict()
                for p,v in zip(model_index_json["parameters"], parameter_definition_str.split("-")):
                    parameter_definition[p["name"]] = v.strip()
            # find the property
            for property_index in range(len(model_index_json["properties"])):
                prop = model_index_json["properties"][property_index]
                if property_name == prop["name"]:
                    # find the file parameter_definition
                    for model_file_index in range(len(model_index_json["files"])):
                        file_info = model_index_json["files"][model_file_index]
                        correct_file = True
                        file_param_values = []
                        if "file-parameter-values" in file_info:
                            file_param_values = file_info["file-parameter-values"]
                        for par_val in file_param_values:
                            value_str = str(par_val["value"]).lower() if type(par_val["value"]) is bool else str(par_val["value"])
                            if str(parameter_definition[par_val["name"]]) != value_str:
                                correct_file = False
                                break
                        if correct_file:
                            # find the open parameter values
                            open_param_values = []
                            if "open-parameter-values" in file_info:
                                open_param_values = file_info["open-parameter-values"]
                            if len(open_param_values) == 0:
                                return Benchmark(model_index_json, model_file_index, 0, property_index)
                            for open_parameter_index in range(len(open_param_values)):
                                correct_open_pars = True
                                if "values" in open_param_values[open_parameter_index]:
                                    for par_val in open_param_values[open_parameter_index]["values"]:
                                        value_str = str(par_val["value"]).lower() if type(par_val["value"]) is bool else str(par_val["value"])
                                        if str(parameter_definition[par_val["name"]]) != value_str:
                                            correct_open_pars = False
                                            break
                                if correct_open_pars:
                                    return Benchmark(model_index_json, model_file_index, open_parameter_index, property_index)
                    raise LookupError("Unable to find parameter definition '{}' for model '{}'.".format(parameter_definition, short_name))
            raise LookupError("Unable to find property '{}' for model '{}'.".format(property_name, short_name))
    raise LookupError("Unable to find benchmark with name '{}'.".format(short_name))





