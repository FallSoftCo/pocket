# Markdown tables in conversations

The candidate parses GitHub Flavored Markdown tables using the table extension matching the existing CommonMark dependency. Both ordinary and immersion messages use the same structured grid. Headers retain their emphasis; cells retain Markdown styles, links, images and declared left/center/right alignment.

Every row shares one column layout and one horizontal scroll position. Short tables fill the available message width. Longer tables keep readable text and reveal further columns by dragging horizontally, with a compact overflow thumb. Cells are at least48dp high; larger text grows rows rather than shrinking the type or hiding content. Intrinsic sizing includes rich styles and longest words, balances long description columns, and reserves source/target rendition widths so a language handoff does not shift the table.

Preview and immersion use a single row-major text flow with tabs between cells and newlines between rows. Exact flattened offsets preserve source-anchored replacements and inline styles. Escaped pipes and pipes inside protected code are handled by the parser; incomplete streaming syntax stays ordinary readable text until a table is valid.

Sizing samples the header and first64 body rows from each reserved rendition. Later content still renders and wraps within those columns; it is not dropped. Very large table composition/performance and real screen-reader traversal remain unqualified.

See [qualification](qualification/markdown-tables-20261012.json). Source/native checks are not a signed release or physical phone delivery. The wider immersion interaction gate remains open.

The parser's extension setup follows [CommonMark Java documentation](https://github.com/commonmark/commonmark-java#extensions); the existing core and added table artifact remain pinned together at0.27.1.
