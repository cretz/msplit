# MSplit

MSplit splits JVM methods that are too large. Often in [ASM](https://asm.ow2.io/) when compiling for the JVM, the method
will be too large, giving the "Method code too large!" exception. The JVM is limits method sizes to 64k. This project
helps get around that.

While the goal is similar to https://bitbucket.org/sperber/asm-method-size, it is much simpler in practice. While it
should work for most use cases in theory, not very many of the quirks have been tested. Please report any issues and
hopefully a test case can be written to try it.

## Usage

Since the code in this repository is only a few files, it was decided not to put it on Maven Central but instead
encourage developers to shade/vendor/embed the code in their own project by just moving the files.

The common way to method is to use `msplit.SplitMethod#split` which accepts the internal class name of the
method, an ASM `MethodNode` to split, `minSize`, `maxSize`, and `atLeastFirst` parameters. `minSize` is the minimum
number of instructions that must be split off, `maxSize` is the maximum number of instructions that can be split off,
and `atLeastFirst` is the number of instructions that, when reached, will be considered the valid set and splitting done
immediately. If `atLeastFirst` is &lt;= 0, the entire set of split points is checked to find the largest within min/max.
An overload of `split` exists that defaults `minSize` to 20% + 1, `maxSize` to 70% + 1, and `atLeastFirst` as the
`maxSize`. The original method is not changed during this process.

This method returns a `Result` which contains the `splitOffMethod`, which is the new method split off from the original,
and `trimmedMethod`, which is the original changed to call the split off method. The method uses the `msplit.Splitter`
class which is an iterator over `msplit.Splitter.SplitPoint` classes which continually return split point possibilities.

The two created methods have all their frames removed and maxs invalid, so when writing with ASM, make sure the class
writer is set to compute frames and maxs.

## How it Works

The algorithm is takes two steps: the first finds valid "split points" where a section of code can be taken out of the
original and put into another method (in `msplit.Splitter`, and the second which uses a split point to do the actual
splitting (in `msplit.SplitMethod`).

The `msplit.Splitter` algorithm is an iterator that iterates over potential split points constrained by a user-supplied
min and max instruction count. The algorithm goes one instruction at a time and:

1. Creates a split point from the current instruction to the max size
1. Changes the end index based on try-catch blocks:
   1. If the try block is completely within the split point, everything is ok except if the catch handler is not at
      which point the end is changed to before the try block to completely exclude it
   1. If the try block starts before the split point but ends inside, the end is reduced to the block's end
   1. If the try block starts inside the split point but ends outside, the end is reduced to before the start
   1. In all cases except the first (i.e. the try block completely inside), if the catch handler jumps inside the block
      then the end is reduced to before the catch handler
1. Reduces the end to just before any jump instruction that jumps out of the split point
1. Reduces the end to just before any target in the split point jumped to by a non-split-point instruction

Then, for that split point, more information is added to it. Specifically:

1. Record the locals that are read
1. Record the locals that are written
1. Record the lowest depth the stack reaches

Finally, build the split point with that information.

The `msplit.SplitMethod` algorithm takes a split point and applies it to the method. It has overloads to find the best
split point based on min/max instruction limits and optionally stopping eagerly when it finds one that reaches a certain
size. Then it creates two methods: the split off method, which is the new one with instructions inside the split point,
and the trimmed method, which is the original one but with the split point instructions removed and replaced with a call
to the split off method.

To create the split off method, a new method is created that accepts the needed start stack types and the read local
types as parameters. It returns an object array which contains the resulting stack items and the resulting written local
types. It is created as a private static synthetic method. When called, the method:

1. Writes all read local parameters to locals
1. Pushes all stack items from parameters on to the stack
1. Uses the split off instructions
1. Creates a return object array
1. Puts the required stack items in the object array
1. Puts the written locals in the object array
1. Adds all try-catch blocks from the original that are fully contained within the split point

All object array work is built to box and unbox as necessary when primitives are encountered.

To create the trimmed method, the method sans instructions and try/catch blocks is copied. When called, the method:

1. Uses all normal instructions up to the split point, keeping track of written locals
1. Pushes all needed read locals on the stack for split off invocation
   1. NOTE: This uses the written local knowledge from the first step to determine if the local is uninitialized. If it
      is uninitialized, it uses the "zero val" of the local instead of loading it. Not yet sure if this is an acceptable
      approach to determine uninitialized locals.
1. Invokes the split off method, which pops/uses the stack then the pushed locals as parameters
1. Takes the result of the split-off method (the object array) and writes the locals back that were changed
1. Then pushes back on the stack the stack portion of the object array

There is more complication than this, but the general idea is here.