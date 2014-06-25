cctext
======

These are some work-in-progress scripts for extracting natural
language text from the Common Crawl corpus.


Building and running
--------------------

This is complicated, because we need to get access to the CLD2
language detection library we need to get java and c++ libraries to
play along with each other, and also because I have no idea what I'm
doing with java build systems::

Step 0: get a recent version of gradle. As of 2014-06-25, Debian
ships gradle 1.5, which **will not** work. I'm using gradle **1.12**.

Then::

    # Get and build CLD2. You must use 'cld2' as the checkout directory,
    # because that's where build.gradle expects to find it.
    svn checkout http://cld2.googlecode.com/svn/trunk cld2
    (cd cld2/internal && ./compile_libs.sh)

    # Build the CLD2 C++ wrapper shim.
    gradle CLD2wrapSharedLibrary

    # Build the Java code, fetch dependencies, and create an
    # invocation script:
    gradle installApp

Now you should be able to do::

    ./run.sh

which is a little script that sets up the rest of the stuff that the
above incantations didn't set up (``LD_LIBRARY_PATH``), and starts a
JVM. (It uses rlwrap to get non-terrible command-line editing, so you
might want to install that too.)

Right now we just drop into a Jython REPL from which you can play
around with the actual code, because I haven't written a main()
function yet.


The CLD2 wrapper
----------------

CLD2 is pretty amazing, but it's written in C++, so using it from Java
is really painful. There are four pieces involved in making it work.

1. ``src/main/java/org/vorpus/cctext/LanguageDetection.java`` defines the
   high-level interface used by the main driver program.

2. It works by calling the auto-generated Bridj wrapper in
   ``lib/CLD2wrap.jar``. This jar file was created by running::

      java -jar jnaerator-0.11-shaded.jar -library CLD2wrap -mode Jar -o lib -package org.vorpus.cld2wrap -f src/CLD2wrap/headers/cld2wrap.h

   You'll have to re-run this if you ever modify ``cld2wrap.h``.

3. The auto-generated wrapper does FFI calls into the hand-written C++
   wrapper in ``src/CLD2wrap/``. This is necessary because CLD2's
   native API involves bizarre and exotic C++ features
   like... ``std::vector`` and ordinary function calls, which Bridj
   apparently cannot handle directly.

4. The hand-written C++ wrapper links against ``libcld2_full.so``, and
   uses CLD's native API directly.


A cry for help
--------------

This is terrible. If you know how to make it not so terrible please help.
