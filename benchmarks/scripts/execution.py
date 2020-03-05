from utility import *
import subprocess, threading, time


class CommandExecution(object):
    """ Represents the execution of a single command line argument. """
    def __init__(self):
        self.timeout = None
        self.return_code = None
        self.output = None
        self.wall_time = None
        self.proc = None

    def stop(self):
        self.timeout = True
        self.proc.kill()

    def run(self, command_line_str, time_limit):
        command_line_list = command_line_str.split()
        command_line_list[0] = os.path.expanduser(command_line_list[0])
        self.proc = subprocess.Popen(command_line_list, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        start_time = time.time()
        timer = threading.Timer(time_limit, self.stop)
        self.timeout = False
        self.output = ""
        timer.start()
        try:
            stdout, stderr = self.proc.communicate()
        except Exception as e:
            self.output = self.output + "Error when executing the command:\n{}\n".format(e)
        finally:
            timer.cancel()
            self.wall_time = time.time() - start_time
            self.return_code = self.proc.returncode
        self.output = self.output + stdout.decode('utf8')
        if len(stderr) > 0:
            self.output = self.output + "\n" + "#"*30 + "Output to stderr" + "#"*30 + "\n" + stderr.decode('utf8')
        if self.timeout and self.wall_time <= time_limit:
            print("WARN: A timeout was triggered although the measured time is {} seconds which is still below the time limit of {} seconds".format(self.wall_time, time_limit))


def execute_command_line(command_line_str : str, time_limit : int):
    """
    Executes the given command line with the given time limit (in seconds).
    :returns the output of the command (including the output to stderr, if present), the runtime of the command and either the return code or None (in case of a timeout)
    """
    execution = CommandExecution()
    execution.run(command_line_str, time_limit)
    if execution.timeout:
        return execution.output, execution.wall_time, None
    else:
        return execution.output, execution.wall_time, execution.return_code

class Execution(object):
    def __init__(self, invocation):
        self.invocation = invocation
        if(invocation.track_id == "often-epsilon-corret-10-min"):
            self.time_limit = settings.time_limit_short()
        else:
            self.time_limit = settings.time_limit()
        self.wall_time = None
        self.logs = None
        self.timeout = None
        self.error = None

    def run(self):
        self.error = False
        self.timeout = False
        self.wall_time = 0.0
        self.logs = []
        for command in self.invocation.commands:
            log, wall_time, return_code = execute_command_line(command, self.time_limit - self.wall_time)
            self.wall_time = self.wall_time + wall_time
            self.logs.append("Command:\t{}\nWallclock time:\t{}\nReturn code:\t{}\nOutput:\n{}\n".format(command, wall_time, return_code, log))
            if return_code is None:
                self.timeout = True
                self.logs[-1] = self.logs[-1] + "\n" + "-"*10 + "\nComputation aborted after {} seconds since the total time limit of {} seconds was exceeded.\n".format(self.wall_time, self.time_limit)
                break
            else:
                self.error = self.error or return_code != 0

    def concatenate_logs(self):
        hline = "\n" + "#" * 40 + "\n"
        return hline.join(self.logs)


