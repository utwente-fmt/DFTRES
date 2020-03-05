from utility import *
from execution import Execution

class Invocation(object):

    def __init__(self, invocation_json = None):
        """ Creates either an empty invocation that can be filled using 'add command' or an invocation from an existing json representation."""
        self.commands = []
        self.track_id = ""
        self.identifier = ""
        if invocation_json != None:
            self.identifier = invocation_json["invocation-id"]
            self.track_id = invocation_json["invocation-track-id"]
            for c in invocation_json["commands"]:
                self.add_command(c)
            if len(self.commands) == 0:
                raise AssertionError("No command defined for the given invocation")


    def add_command(self, command):
        if not isinstance(command, str):
            raise AssertionError("The given command is not a string!")
        self.commands.append(command)

    def to_json(self):
        return OrderedDict([("invocation-id", self.identifier), ("invocation-track-id", self.track_id), ("commands", self.commands)])

    def execute(self):
        execution = Execution(self)
        execution.run()
        return execution


