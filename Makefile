SOURCE_DIR = src/
CLASS_DIR = bin/
SOURCES = $(subst $(SOURCE_DIR),,$(shell find $(SOURCE_DIR) -type f -name '*.java' -print ))
OBJECTS = $(addprefix $(CLASS_DIR), $(addsuffix .class, $(basename $(SOURCES))))
MAIN_CLASS = Main
JFLAGS = -Xlint:deprecation -g

.PHONY: jar all clean dir main

main: dir all

jar: DFTRES.jar

DFTRES.jar: dir $(OBJECTS)
	@jar cfe DFTRES.jar $(MAIN_CLASS) -C $(CLASS_DIR) .
	@echo 'JAR   $@'

all: dir $(OBJECTS)

dir:
	@mkdir -p $(CLASS_DIR)

$(CLASS_DIR)%.class: $(SOURCE_DIR)%.java
	@javac -Xlint:unchecked -sourcepath $(SOURCE_DIR) $(JFLAGS) -d $(CLASS_DIR) $(patsubst $(SOURCE_DIR)/%.java,%.java , $<)
	@echo JAVAC $(@:$(CLASS_DIR)%=%)

clean:
	@$(RM) -r $(CLASS_DIR)
	@echo 'RM    $(CLASS_DIR)'
	@$(RM) -r DFTRES.jar
	@echo 'RM    DFTRES.jar'
