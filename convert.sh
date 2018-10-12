#!/bin/sh

BASE="${1%.dft}";
if [ -e "$BASE.exp" ]; then
	echo "$BASE already converted, skipping";
	continue;
fi
echo $BASE;
dftcalc -x --mrmc -x "$1";
mv "output/$BASE.exp" "$BASE.exp";
FILENAMES=$( grep -o '"[^"]*\.bcg"' "$BASE.exp" | sed -e 's/"//g' );
for file in $FILENAMES; do
	MODEL=${file##*/};
	AUT=${MODEL%.bcg}.aut;
	bcg_io "$file" "$AUT";
	sed -e "s:\"${file}\":\"${AUT}\":g" "$BASE.exp" > "$BASE.tmp";
	mv "$BASE.tmp" "$BASE.exp";
done
