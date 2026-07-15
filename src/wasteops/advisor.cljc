(ns wasteops.advisor
  "WasteOpsAdvisor -- the *contained intelligence node* for the
  ISIC-3821 non-hazardous-waste treatment-and-disposal operations-
  coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: facility-record logging (intake-volume/sorting-yield/
  disposal-method data), maintenance scheduling (sorting/incineration/
  composting-equipment), safety-concern flagging (environmental-
  contamination/fire-hazard/emissions-exceedance), and outbound
  shipment coordination (recovered-material/residual-waste). CRITICAL:
  it is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record and NEVER
  a direct actuation -- every proposal's `:effect` is always
  `:propose`. Every output is censored downstream by
  `wasteops.governor` before anything touches the SSoT.

  This advisor NEVER drafts sorting/incineration-equipment control
  (direct actuation) or any environmental-permit-authority decision
  (permit issuance, disposal-authorization, environmental-compliance
  determination) -- those are permanently out of scope for this actor,
  not merely un-implemented. `wasteops.governor`'s
  `scope-exclusion-violations` independently re-scans every proposal
  for exactly this failure mode (a compromised or confused advisor
  drifting into scope it must never touch) and HARD-holds it,
  regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op          kw             ; echoes the request op
     :facility-id str
     :summary     str            ; human-facing draft / finding
     :rationale   str            ; why -- SCANNED by the scope-exclusion gate
     :cites       [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect      :propose       ; ALWAYS :propose -- never a direct actuation
     :value       map            ; the draft payload a human/system would review
     :confidence  0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-facility-record
  "Draft an intake-volume/sorting-yield/disposal-method facility-record
  log entry. Pure logging of ALREADY-OBSERVED data -- never a decision
  about sorting or incineration equipment operation."
  [_db {:keys [facility-id patch]}]
  {:op          :log-facility-record
   :facility-id facility-id
   :summary     (str facility-id " の施設記録(搬入量/選別歩留まり/処分方法データ)を提案: " (pr-str (keys patch)))
   :rationale   "入力された搬入量/選別歩留まり/処分方法データの記録提案のみ。新規事実の生成なし。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.93})

(defn- propose-maintenance
  "Draft a sorting/incineration/composting-equipment maintenance
  scheduling proposal (a calendar entry/work order draft, never a
  direct dispatch or equipment actuation)."
  [_db {:keys [facility-id patch]}]
  {:op          :schedule-maintenance
   :facility-id facility-id
   :summary     (str facility-id " の選別/焼却/堆肥化設備の保守予定を提案: " (pr-str (keys patch)))
   :rationale   "選別・焼却・堆肥化設備の保守スケジュールの提案のみ。実際の保守作業実施の判断は人間が行う。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.88})

(defn- propose-safety-concern
  "Surface an environmental-contamination/fire-hazard/emissions-
  exceedance concern for HUMAN triage. This op ALWAYS escalates in
  `wasteops.governor` -- never auto-committed at any phase
  (`wasteops.phase`) -- regardless of how confident the advisor is
  that the concern is real or minor. The advisor itself makes NO
  environmental-compliance determination; it only surfaces the
  observation."
  [_db {:keys [facility-id patch]}]
  {:op          :flag-safety-concern
   :facility-id facility-id
   :summary     (str facility-id " の環境上の懸念(汚染/火災リスク/排出超過)を提起: " (pr-str (keys patch)))
   :rationale   "観測された懸念事象の提起のみ。環境コンプライアンス評価・是正措置の決定は行わない -- 常に人間審査が必要。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  (get patch :confidence 0.9)})

(defn- propose-shipment
  "Draft an outbound recovered-material/residual-waste shipment
  coordination proposal -- scheduling/logistics only, never the
  underlying transport-permit or environmental-compliance decision
  itself."
  [_db {:keys [facility-id patch]}]
  {:op          :coordinate-shipment
   :facility-id facility-id
   :summary     (str facility-id " の再生資材/残渣廃棄物の搬出調整を提案: " (pr-str (keys patch)))
   :rationale   "再生資材・残渣廃棄物の搬出物流調整の提案のみ。実際の搬出承認は人間が行う。"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.87})

(defn- propose-out-of-scope
  "Test/failure-mode hook: drafts a proposal that touches a
  permanently-excluded scope area (sorting/incineration-equipment
  control or an environmental-permit-authority decision) so the
  governor's `scope-exclusion-violations` HARD block can be exercised
  directly, the same 'exercise the failure mode directly' discipline
  every sibling actor's own sim/test suite uses. Never reachable from
  the closed op allowlist in normal operation -- only via the
  `:out-of-scope?` request flag."
  [_db {:keys [facility-id patch]}]
  {:op          :schedule-maintenance
   :facility-id facility-id
   :summary     (str facility-id " の焼却設備制御シーケンスの変更を提案")
   :rationale   "対象facilityのincineration equipment controlとsorting line actuationのタイミングを調整済み"
   :cites       [facility-id]
   :effect      :propose
   :value       (merge {:facility-id facility-id} patch)
   :confidence  0.9})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :facility-id str :patch map ...}"
  [db {:keys [op out-of-scope?] :as request}]
  (cond
    out-of-scope?                 (propose-out-of-scope db request)
    (= op :log-facility-record)   (propose-facility-record db request)
    (= op :schedule-maintenance)  (propose-maintenance db request)
    (= op :flag-safety-concern)   (propose-safety-concern db request)
    (= op :coordinate-shipment)   (propose-shipment db request)
    :else {:op op :facility-id (:facility-id request)
           :summary "未対応の操作" :rationale (str "closed allowlist に無い操作: " op)
           :cites [] :effect :propose :value {} :confidence 0.0}))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

;; ----------------------------- real-LLM advisor (production seam) -----------------------------

(def ^:private system-prompt
  (str "あなたは非有害廃棄物の処理・処分事業の運営コーディネーション助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "許可された操作は :log-facility-record / :schedule-maintenance / "
       ":flag-safety-concern / :coordinate-shipment の4つのみです。"
       "選別・焼却設備の直接制御や、環境許可機関の判断"
       "(環境許可発行/処分許可発行/環境コンプライアンス決定)には絶対に触れてはいけません。"
       "キー: :op :facility-id :summary :rationale :cites :effect(常に :propose) "
       ":value :confidence(0..1)。"))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the governor escalates/holds --
  an LLM hiccup can never bypass governance."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :propose)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :propose :value {} :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ _st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n facility: " (:facility-id req)
                                              "\n patch: " (pr-str (:patch req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :facility-id (:facility-id request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
