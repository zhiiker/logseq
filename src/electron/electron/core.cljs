(ns electron.core
  (:require ["electron" :refer [app BrowserWindow crashReporter]]))


(def main-window (atom nil))

(defn init-browser []
  (reset! main-window (BrowserWindow.
                        (clj->js {:width 800
                                  :height 600})))
  ; Path is relative to the compiled js file (main.js in our case)
  ^html (.loadURL @main-window (str "http://localhost:3000"))
  ^js (.on @main-window "closed" #(reset! main-window nil)))

(defn main []
  ; CrashReporter can just be omitted
  (.start crashReporter
    (clj->js
      {:companyName "Logseq"
       :productName "logseq"
       :submitURL "https://example.com/submit-url"
       :autoSubmit false}))

  (.on app "window-all-closed" #(when-not (= js/process.platform "darwin")
                                  (.quit app)))
  (.on app "ready" init-browser))
