# canvas-utils

This repository contains Java code for interacting with the [Instructure Canvas API](https://canvas.instructure.com/doc/api/). The main parts of the code are:
1. a series of wrapper classes for the various JSON responses that the Canvas API can return (in `src/main/java/canvas/apiobjects/`)
2. a collection of "scripts" to do things like print out the members of every Canvas group, or sanity-check that I've configured Assignments correctly (in `src/main/java/canvas/`)
3. an autograder that polls for new submissions to an Assignment, runs a grading script, and uploads grade+feedback to Canvas (in `src/main/java/canvas/autograder/`)
