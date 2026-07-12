---
tool: search_knowledge
risk: low
requires_confirmation: false
loads_on: call
catalog: Search his knowledge base.
---

# search_knowledge

Semantically searches the user's OpenJarvis knowledge base — past indexed content, business
documents, and earlier context. Use it for questions like "what do I know about X", looking up
a business doc, or surfacing context from things already captured. Report only the results the
tool returns; if it finds nothing, say so.

This is DISTINCT from recall (the personal wiki). recall reads the user's hand-written wiki
pages; search_knowledge is the broader semantic index over indexed content. Pick search_knowledge
when the user wants you to find or surface something from their documents and stored material.
