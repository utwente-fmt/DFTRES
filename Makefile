SOURCE_DIR = src/
CLASS_DIR = bin/
SOURCES = $(subst $(SOURCE_DIR),,$(shell find $(SOURCE_DIR) -type f -name '*.java' -print ))
SOURCES += nl/utwente/ewi/fmt/EXPRES/Version.java
OBJECTS = $(addprefix $(CLASS_DIR), $(addsuffix .class, $(basename $(SOURCES))))
COMMIT = $(shell git rev-parse HEAD)
VERSION_MAJOR=1
VERSION_MINOR=0
VERSION_PATCH=0
VERSIONSTRING="${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_PATCH}"
ifeq ($(shell git status --porcelain),)
else
	VERSIONSTRING += + "+" + "$(COMMIT)".substring(0,8) + "-dirty"
endif
MAIN_CLASS = Main
JFLAGS = -Xlint:deprecation -g

.PHONY: jar all clean dir main

main: dir all

jar: DFTRES.jar

DFTRES.jar: dir $(OBJECTS)
	@jar cfe DFTRES.jar $(MAIN_CLASS) -C $(CLASS_DIR) .
	@echo 'JAR   $@'

all: dir $(OBJECTS)

dir: $(SOURCE_DIR)/nl/utwente/ewi/fmt/EXPRES/Version.java
	@mkdir -p $(CLASS_DIR)

$(SOURCE_DIR)/nl/utwente/ewi/fmt/EXPRES/Version.java: FORCE
	@echo "package nl.utwente.ewi.fmt.EXPRES;" > $@
	@echo "public class Version {" >> $@
	@echo '	public static final String version = ${VERSIONSTRING};' >> $@
	@echo "}" >> $@

$(CLASS_DIR)%.class: $(SOURCE_DIR)%.java
	@javac -Xlint:unchecked -sourcepath $(SOURCE_DIR) $(JFLAGS) -d $(CLASS_DIR) $(patsubst $(SOURCE_DIR)/%.java,%.java , $<)
	@echo JAVAC $(@:$(CLASS_DIR)%=%)

clean:
	@$(RM) -r $(CLASS_DIR)
	@echo 'RM    $(CLASS_DIR)'
	@$(RM) -r DFTRES.jar
	@echo 'RM    DFTRES.jar'

FORCE:
