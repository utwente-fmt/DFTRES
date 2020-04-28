Dynamic Fault Tree Rare Event Simulator
=======================================

![Compile and Test](https://github.com/utwente-fmt/DFTRES/workflows/Compile%20and%20Test/badge.svg)

This program uses importance sampling to improve the estimations by
Monte Carlo simulations of (repairable) dynamic fault trees.


Compilation
-----------

Running 'make jar' should create the DFTRES.jar program.

Use
---

The standard syntax is:

`java -jar DFTRES.jar [options] [<input>.exp | <input>.jani | <input.dft>]`

NOTE: input from .dft files requires a working DFTCalc version.

Run `java -jar DFTRES.jar` to see available options.
