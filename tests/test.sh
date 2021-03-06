#!/bin/bash

REL_ERROR_BOUND="10^-2";
DFTRES_CMD="java -jar ../DFTRES.jar -s 0 -p 4"
DFTRES_OPTS="--relErr 1e-1"

all_match () {
	VAL=$1;
	while [ "$#" -gt 1 ]; do
		shift;
		GREP_CMD="grep -F"
		PATTERN="$1";
		if [ "${1#!\"}" != "$1" ]; then
			GREP_CMD="$GREP_CMD -v";
			PATTERN="${1#!\"}";
		elif [ "${1#+\"}" != "$1" ]; then
			PATTERN="${1#+\"}";
		else
			echo "Invalid expected failure criterion: '$1'"
			exit 1;
		fi
		if [ "$PATTERN" == "${PATTERN%\"}" ]; then
			echo "Invalid expected failure criterion: '$1'"
			exit 1;
		fi
		PATTERN="${PATTERN%\"}";
		if ! echo "$VAL" | $GREP_CMD -e "$PATTERN" > /dev/null; then
			return 1;
		fi
	done
	return 0;
}

expect_fail () {
	DFT="$1";
	ARGS="$2";
	while read LINE; do
		FFILE=$(echo "$LINE" | sed -e 's/[[:space:]].*//');
		if [ "$FFILE" != "$DFT" ]; then
			continue;
		fi
		NEED=$(echo "$LINE" | sed -e 's/^[^[:space:]]*[[:space:]]*//');
		if all_match "$ARGS" $NEED; then
			return 0;
		fi
	done
	return 1;
}

# Split an interval value into lower and upper bounds.
# Usage: split_interval "1.23[45; 67]e-1" VAR_LOW VAR_HIGH
# sets $VAR_LOW to '1.2345e-1' and $VAR_HIGH to '1.2367e-1'.
split_interval () {
	LOW=$(echo "$1" | sed -e 's/; *.*]//g' | sed -e 's/\[//g');
	HIGH=$(echo "$1" | sed -e 's/\[.*; *//g' | sed -e 's/]//g');
	eval "$2=\"$LOW\"";
	eval "$3=\"$HIGH\"";
}

# Compare DFTRES's output value to the given reference value.
# Use: compare "$VALUE" "$REF"
# Return values:
# 0:   Returned value is in the reference range
# 1:   Returned value overlaps the reference range
# 2:   Returned value is outside the reference range, but within
#      $REL_ERROR_BOUND of it.
# 3:   Returned value is more than $REL_ERROR_BOUND outside the reference
#      range or is not a valid number.
compare () {
	if [ -z "$1" ]; then
		return 3;
	fi
	VAL="$1";
	REF="$2";
	split_interval "$VAL" VAL_LOW VAL_HIGH;
	split_interval "$REF" REF_LOW REF_HIGH;
	for i in VAL_LOW VAL_HIGH REF_LOW REF_HIGH; do
		CONV=$(eval "echo \$$i | sed -e 's/[eE]/*10^/'");
		eval "$i=\"$CONV\"";
	done
	EXACT=$(printf "scale=100\n$VAL_LOW >= $REF_LOW && $VAL_HIGH <= $REF_HIGH\n" | bc);
	if [ "$EXACT" = "1" ]; then
		return 0;
	fi
	OVERLAP=$(printf "scale=100\n($VAL_LOW <= $REF_LOW && $VAL_HIGH >= $REF_LOW) || ($VAL_LOW <= $REF_HIGH && $VAL_HIGH >= $REF_HIGH)\n" | bc);
	if [ "$OVERLAP" = "1" ]; then
		return 1;
	fi
	EXPANDED_LOW="($REF_LOW - ($REF_LOW * $REL_ERROR_BOUND))";
	EXPANDED_HIGH="($REF_HIGH + ($REF_HIGH * $REL_ERROR_BOUND))";
	OVERLAP=$(printf "scale=100\n($VAL_LOW >= $EXPANDED_LOW && $VAL_HIGH <= $EXPANDED_HIGH) || ($VAL_LOW <= $EXPANDED_LOW && $VAL_HIGH >= $EXPANDED_LOW) || ($VAL_LOW <= $EXPANDED_HIGH && $VAL_HIGH >= $EXPANDED_HIGH)\n" | bc);
	if [ "$OVERLAP" = "1" ]; then
		return 2;
	fi
	return 3;
}

do_tests() {
	while read LINE; do
		TESTS_TOTAL=$(( $TESTS_TOTAL + 1 ));
		FILE=$(echo "$LINE" | sed -e 's/\s.*//');
		OPTS=$(echo "$LINE" | sed -e 's/^[^"]*"//' | sed -e 's/".*//');
		REF=$(echo "$LINE" | sed -e 's/^.*"\s*//');
		printf "Test $FILE ($DFTRES_OPTS $OPTS)";
		RESULT=$($DFTRES_CMD $DFTRES_OPTS $OPTS "$FILE" 2>/dev/null | grep -o "CI: .*]" | sed -e 's/CI: //' | sed -e 's/,/;/');
		if expect_fail "$FILE" "$DFTRES_OPTS $OPTS" <expect-fail.txt; then
			if [ -z "$RESULT" ]; then
				MSG="failed as expected";
				VERDICT="PASS";
			else
				MSG="got $RESULT, expected failure";
				VERDICT="FAIL";
			fi
		else
			compare "$RESULT" "$REF";
			OUTCOME=$?
			if [ "$OUTCOME" = "0" ]; then
				VERDICT="PASS";
				MSG="";
			elif [ "$OUTCOME" = "1" ]; then
				VERDICT="PASS";
				MSG="overlap";
			elif [ "$OUTCOME" = "2" ] && [ "$BOUND_OK" = "1" ]; then
				VERDICT="PASS";
				MSG="within bounds";
			elif [ "$OUTCOME" = "2" ]; then
				VERDICT="FAIL";
				MSG="within bounds, got $RESULT, want $REF";
			else
				VERDICT="FAIL";
				if ! [ -z "$RESULT" ]; then
					MSG="got $RESULT, want $REF";
				else
					MSG="Got no result";
				fi
			fi
		fi
		printf "\r$VERDICT: $FILE ($DFTRES_OPTS $OPTS)";
		if ! [ -z "$MSG" ]; then
			printf ", $MSG";
		fi
		printf "\n";
		if [ "$VERDICT" = "PASS" ]; then
			TESTS_PASSED=$(( $TESTS_PASSED + 1 ));
		else
			TESTS_FAILED=$(( $TESTS_FAILED + 1 ));
		fi
	done
}

TESTS_FAILED=0
TESTS_PASSED=0
TESTS_TOTAL=0
BOUND_OK=1

DO_DFTCALC_TESTS=0;
DO_STORM_TESTS=0;
while [ "$1" = "--dft" ]; do
	if [ "$2" = "dftcalc" ]; then
		DO_DFTCALC_TESTS=1;
	elif [ "$2" = "storm" ]; then
		DO_STORM_TESTS=1;
	fi
	shift 2;
done
if [ "$1" = "--" ]; then
	shift;
fi
if [ "$#" -gt 0 ]; then
	DFTRES_OPTS="$*";
fi
do_tests < tests.txt
if [ "$DO_DFTCALC_TESTS" = 1 ]; then
	do_tests < dfttests.txt
else
	do_tests < <(sed -e 's/\(.*\)\.dft/expfiles\/\1.exp/' dfttests.txt)
fi
if [ "$DO_STORM_TESTS" = 1 ]; then
	DFTRES_OPTS="$DFTRES_OPTS --storm" do_tests < dfttests.txt
else
	DFTRES_OPTS="$DFTRES_OPTS --storm" do_tests < <(sed -e 's/\(.*\)\.dft/janifiles\/\1.jani/' dfttests.txt)
fi
printf "\n";
if [ "$TESTS_FAILED" = "0" ]; then
	echo "$TESTS_TOTAL tests executed, all passed";
	exit 0;
else
	echo "$TESTS_TOTAL tests executed, $TESTS_FAILED failed";
	exit 1;
fi
