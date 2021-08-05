# Athens exporter

Standalone tool that converts an
[Athens Research](https://github.com/athensresearch/athens) database into a
[logseq](https://github.com/logseq/logseq) directory.


## Installation

Clone to your root directory

```
$ git clone https://github.com/shepheb/athens-export.git
```
## Usage

Install dependencies
- [Clojure CLI tool](https://clojure.org/guides/getting_started), `clj`.



```cd athens-export``` and then, in the same window, run the following code, making sure to enter the path to your Athens DB and target directory. 
```
$ clj -X athens.export/export \
  :athens '"path/to/athens/index.transit"' \
  :logseq '"path/to/empty/logseq/dir"'
```

(Yes, with the weird double quoting - we want to pass the literal `"`s to
Clojure, so we wrap them with `'` so the shell doesn't eat them.)

## Features

This transforms an Athens database and exports it as Markdown files in a
directory tree, suitable for importing into logseq.

- Pages named eg. `July 16, 2021` in Athens are recognized as journal pages and
  written accordingly for logseq (eg. `journals/2021_07_16.md`).
- Block references are detected, and rewritten with logseq-style UUIDs.
- File names and `title::` metadata are properly written
  - `/` becomes `.`
  - `:` becomes `_`
  - `.` is preserved but the `title::` is written explicitly

## Caveats

There are several known weaknesses of this tool.

- `TODO`/`DONE` in Athens is rudimentarily converted to logseq's format using
  TODO and DONE markers. Keep in mind that logseq will only recognize them if
  they are the begining of a block (Athens allows you to randomly add them
  anywhere on the block, if you rely on that, you will have to manually fix
  your files).
- iframe and YouTube embeds are not converted
  - they are visible (flagged as errors) in logseq, and can be edited to work.
- code blocks are clumsy in Athens, and show up misformatted in logseq
  - but they render legibly, and can be fixed by hand in logseq.
- there may be more file name special characters I don't know about

PRs to fix any of these issues would be welcome!

