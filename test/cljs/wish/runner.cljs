(ns wish.runner
  (:require [doo.runner :refer-macros [doo-tests doo-all-tests]]
            [wish.events-test]
            [wish.inventory-test]
            [wish.providers.gdrive.api-test]
            [wish.sheets.dnd5e.builder-test]
            [wish.sheets.dnd5e.data-test]
            [wish.sheets.dnd5e.events-test]
            [wish.sheets.dnd5e.overlays-test]
            [wish.sheets.dnd5e.overlays.custom-item-test]
            [wish.sheets.dnd5e.subs-test]
            [wish.sheets.dnd5e.util-test]
            [wish.sources.compiler-test]
            [wish.sources.compiler.entity-mod-test]
            [wish.sources.compiler.feature-test]
            [wish.sources.compiler.fun-test]
            [wish.sources.compiler.limited-use-test]
            [wish.sources.compiler.lists-test]
            [wish.subs-test]
            [wish.util-test]
            [wish.util.formatted-test]
            [wish.views.sheet-builder-util-test]))

(doo-all-tests #"wish\..*-test")
