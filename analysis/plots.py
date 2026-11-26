#!/usr/bin/env python3
"""Grafici della Milestone 2 a partire dai file ml_*.csv del passo `ml`.

Uso:
    python3 analysis/plots.py [cartella_output]

La cartella di default e' `output`. I grafici (PNG) e la tabella per release vengono scritti in
`<cartella_output>/plots/`.

Figure prodotte:
  cv_migliori.png              boxplot sui 100 fold delle versioni migliori dei tre classificatori
  cv_configurazioni_<M>.png    boxplot delle 18 configurazioni, una figura per metrica (appendice)
  holdout_test.png             holdout: Kappa, AUC e NPofB20 delle 18 configurazioni con IC al 95%
  holdout_test_altre.png       holdout: Accuracy, Precision, Recall e NPofB10 (appendice)
  effetto_tecniche.png         differenza rispetto alla baseline di ogni classificatore (CV e holdout)
  cv_vs_holdout.png            Kappa e NPofB20: media nella CV contro valore sul test dell'holdout
  selezione_cv.png             quante volte ogni configurazione e' stata scelta nei 100 fold
  holdout_per_release.png      Kappa e NPofB20 delle versioni migliori su ciascuna release di test
  holdout_per_release.csv      tutte le metriche di tutte le configurazioni per release di test

Gli intervalli dell'holdout sono intervalli bootstrap (percentili, 2.000 ricampionamenti delle righe del
test) di ciascun modello: servono a leggere l'incertezza. I confronti tra modelli si leggono nei file
ml_*_tests.csv, che usano test appaiati.
"""

import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from matplotlib.colors import LinearSegmentedColormap, TwoSlopeNorm  # noqa: E402
from matplotlib.lines import Line2D  # noqa: E402

# ---------------------------------------------------------------- stile

CLASSIFIERS = ["RandomForest", "NaiveBayes", "IBk"]
COLORS = {"RandomForest": "#257de0", "NaiveBayes": "#f47230", "IBk": "#00b894"}
TEXT = "#191919"
TEXT_2 = "#666666"
GRID = "#e6e6e6"
REFERENCE = "#8c8c8c"

METRICS = ["Accuracy", "Precision", "Recall", "AUC", "Kappa", "NPofB10", "NPofB20"]
FEATURE_SELECTION = ["NONE", "CFS"]
BALANCING = ["NONE", "UNDERSAMPLING", "SMOTE"]
BOOTSTRAP = 2000
SEED = 1

plt.rcParams.update({
    "figure.dpi": 110,
    "savefig.dpi": 200,
    "savefig.bbox": "tight",
    "savefig.facecolor": "white",
    "font.family": "DejaVu Sans",   # il default di matplotlib: stesso aspetto su ogni sistema
    "font.size": 9,
    "axes.titlesize": 10,
    "axes.titleweight": "bold",
    "axes.labelcolor": TEXT_2,
    "axes.edgecolor": "#bdbdbd",
    "axes.linewidth": 0.8,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.grid": True,
    "axes.axisbelow": True,
    "grid.color": GRID,
    "grid.linewidth": 0.6,
    "xtick.color": TEXT_2,
    "ytick.color": TEXT_2,
    "text.color": TEXT,
    "legend.frameon": False,
})


def config_label(fs, balancing):
    """Nome breve di una coppia di tecniche, es. "CFS + SMOTE"; senza tecniche "Baseline"."""
    parts = []
    if fs == "CFS":
        parts.append("CFS")
    if balancing == "UNDERSAMPLING":
        parts.append("Undersampling")
    elif balancing == "SMOTE":
        parts.append("SMOTE")
    return " + ".join(parts) if parts else "Baseline"


def configurations():
    """Le 18 configurazioni nell'ordine del passo ml: (classificatore, feature selection, balancing)."""
    return [(c, fs, b) for c in CLASSIFIERS for fs in FEATURE_SELECTION for b in BALANCING]


def select(df, classifier, fs, balancing):
    return df[(df.Classifier == classifier) & (df.FeatureSelection == fs) & (df.Balancing == balancing)]


def legend_handles():
    return [Line2D([0], [0], marker="s", linestyle="", markersize=8, markerfacecolor=COLORS[c],
                   markeredgecolor=COLORS[c], label=c) for c in CLASSIFIERS]


def style_box(bp, color):
    for box in bp["boxes"]:
        box.set(facecolor=color + "59", edgecolor=color, linewidth=1.2)
    for part in ("whiskers", "caps"):
        for line in bp[part]:
            line.set(color=color, linewidth=1)
    for median in bp["medians"]:
        median.set(color=TEXT, linewidth=1.6)
    for flier in bp["fliers"]:
        flier.set(marker="o", markersize=3, markerfacecolor="white", markeredgecolor=color, alpha=0.9)


# ---------------------------------------------------------------- lettura

def read(out, name):
    path = out / name
    if not path.exists():
        sys.exit(f"File mancante: {path} (eseguire prima il passo ml)")
    # round_trip: legge i double esattamente come li ha scritti Java (serve ai pareggi di AUC e NPofBX)
    return pd.read_csv(path, na_values="?", dtype={"K": str}, keep_default_na=False, float_precision="round_trip")


def release_names(out):
    """ReleaseID -> versione, da releases_dataset.csv se presente (passo 1)."""
    path = out / "releases_dataset.csv"
    if not path.exists():
        return {}
    r = pd.read_csv(path, dtype=str)
    index = r["Index"].astype(int)
    offset = 1 if index.min() == 0 else 0      # ReleaseID parte da 1
    return dict(zip(index + offset, r["Version"]))


# ---------------------------------------------------------------- metriche dalle predizioni (holdout)

class PredictionMetrics:
    """Metriche di un modello sulle righe di test, per molti vettori di pesi alla volta (bootstrap).

    Stesse definizioni del codice Java (MetricsCalculator): Kappa di Cohen, AUC con i pareggi contati a meta',
    NPofBX ordinando per probabilita' / max(Size, 1) decrescente, poi probabilita' decrescente, poi ordine del
    file; la classe che farebbe superare l'X% delle righe non viene ispezionata e l'ispezione si ferma.
    """

    def __init__(self, g):
        g = g.reset_index(drop=True)
        self.actual = (g.Actual == "yes").to_numpy()
        self.predicted = (g.Predicted == "yes").to_numpy()
        self.prob = g.ProbabilityYes.to_numpy(dtype=float)
        self.size = g.Size.to_numpy(dtype=float)
        n = len(g)
        idx = np.arange(n)
        density = self.prob / np.maximum(self.size, 1)
        self.effort_order = np.lexsort((idx, -self.prob, -density))
        self.auc_order = np.argsort(self.prob, kind="stable")
        sorted_prob = self.prob[self.auc_order]
        self.tie_starts = np.flatnonzero(np.r_[True, sorted_prob[1:] != sorted_prob[:-1]])

    def compute(self, w):
        """w: matrice (ricampionamenti x righe) di pesi interi; restituisce un dict metrica -> array."""
        a = self.actual
        p = self.predicted
        tp = w @ (a & p)
        fp = w @ (~a & p)
        fn = w @ (a & ~p)
        tn = w @ (~a & ~p)
        total = tp + fp + fn + tn
        with np.errstate(invalid="ignore", divide="ignore"):
            observed = (tp + tn) / total
            chance = ((tp + fn) * (tp + fp) + (tn + fp) * (tn + fn)) / (total * total)
            kappa = np.where(chance < 1, (observed - chance) / (1 - chance), 1.0)
            out = {
                "Accuracy": observed,
                "Precision": tp / (tp + fp),
                "Recall": tp / (tp + fn),
                "Kappa": kappa,
                "AUC": self._auc(w),
            }
        for x in (10, 20):
            out[f"NPofB{x}"] = self._npofb(w, x)
        return out

    def _auc(self, w):
        ws = w[:, self.auc_order]
        a = self.actual[self.auc_order]
        pos = np.add.reduceat(ws * a, self.tie_starts, axis=1)
        neg = np.add.reduceat(ws * ~a, self.tie_starts, axis=1)
        below = np.cumsum(neg, axis=1) - neg
        num = (pos * (below + 0.5 * neg)).sum(axis=1)
        return num / (pos.sum(axis=1) * neg.sum(axis=1))

    def _npofb(self, w, percent):
        ws = w[:, self.effort_order].astype(float)
        a = self.actual[self.effort_order]
        s = self.size[self.effort_order]
        lines = ws * s
        cum = np.cumsum(lines, axis=1)
        budget = cum[:, -1:] * percent                       # confronto con righe * 100, come in Java
        full = cum * 100 <= budget                            # prefisso: classi ispezionate per intero
        found = (full * ws * a).sum(axis=1)
        # prima riga che sfora: si ispezionano solo le copie (bootstrap) che stanno nel budget
        first = np.where(full.all(axis=1), -1, np.argmin(full, axis=1))
        rows = np.flatnonzero(first >= 0)
        if rows.size:
            j = first[rows]
            prev = cum[rows, j] - lines[rows, j]
            with np.errstate(invalid="ignore", divide="ignore"):
                fit = np.floor((budget[rows, 0] - prev * 100) / (100 * s[j]))
            fit = np.clip(np.nan_to_num(fit), 0, ws[rows, j])
            found[rows] += fit * a[j]
        buggy = w @ self.actual
        with np.errstate(invalid="ignore", divide="ignore"):
            return found / buggy


def check_against_java(intervals, test):
    """Confronta i valori ricalcolati dalle predizioni con ml_holdout_test.csv e li sostituisce con quelli del
    passo ml. Le metriche di soglia devono coincidere; AUC e NPofBX possono differire di poco se il CSV ha le
    probabilita' arrotondate a 6 decimali (versioni precedenti del passo ml), perche' l'arrotondamento crea
    pareggi. Una differenza grande indica un errore e ferma lo script."""
    worst = {}
    for c, fs, b in configurations():
        row = select(test, c, fs, b).iloc[0]
        for metric in METRICS:
            mask = ((intervals.Classifier == c) & (intervals.FeatureSelection == fs)
                    & (intervals.Balancing == b) & (intervals.Metric == metric))
            v = intervals.loc[mask, "Value"].iloc[0]
            d = abs(v - row[metric])
            worst[metric] = max(worst.get(metric, 0), d)
            tolerance = 1e-6 if metric in ("Accuracy", "Precision", "Recall", "Kappa") else 0.01
            if d > tolerance:
                sys.exit(f"Valore diverso da ml_holdout_test.csv: {c}/{fs}/{b} {metric} {v} != {row[metric]}")
            intervals.loc[mask, "Value"] = row[metric]
    print("Differenza massima tra metriche ricalcolate e ml_holdout_test.csv: "
          + ", ".join(f"{m} {d:.4f}" for m, d in worst.items()))


def holdout_intervals(pred):
    """Valore e intervallo bootstrap al 95% di ogni metrica per ogni configurazione dell'holdout."""
    rng = np.random.default_rng(SEED)
    n = select(pred, *configurations()[0]).shape[0]
    weights = np.stack([np.bincount(rng.integers(0, n, n), minlength=n) for _ in range(BOOTSTRAP)])
    ones = np.ones((1, n))
    rows = []
    for c, fs, b in configurations():
        m = PredictionMetrics(select(pred, c, fs, b))
        point = m.compute(ones)
        boot = m.compute(weights)
        for metric in METRICS:
            values = boot[metric][~np.isnan(boot[metric])]
            rows.append(dict(Classifier=c, FeatureSelection=fs, Balancing=b, Metric=metric,
                             Value=point[metric][0], Low=np.percentile(values, 2.5),
                             High=np.percentile(values, 97.5)))
    return pd.DataFrame(rows)


def smallest_first(g, percent):
    """Riferimento senza modello: classi dalla piu' piccola, a parita' ordine del file."""
    g = g.reset_index(drop=True)
    order = np.lexsort((np.arange(len(g)), g.Size.to_numpy()))
    s = g.Size.to_numpy(dtype=float)[order]
    a = (g.Actual == "yes").to_numpy()[order]
    cum = np.cumsum(s)
    full = cum * 100 <= cum[-1] * percent
    return a[full].sum() / a.sum()


# ---------------------------------------------------------------- figure

def fig_cv_best(folds, out):
    refs = folds.drop_duplicates(["Repetition", "Fold"])
    fig, axes = plt.subplots(2, 4, figsize=(12, 6.2))
    for ax, metric in zip(axes.flat, METRICS):
        data = [folds[(folds.Classifier == c) & (folds.BestForClassifier == "yes")][metric].dropna() for c in
                CLASSIFIERS]
        for i, (c, d) in enumerate(zip(CLASSIFIERS, data)):
            bp = ax.boxplot([d], positions=[i], widths=0.55, patch_artist=True)
            style_box(bp, COLORS[c])
        if metric.startswith("NPofB"):
            x = metric[5:]
            ax.axhline(refs[f"SmallestFirstNPofB{x}"].mean(), color=REFERENCE, linestyle="--", linewidth=1)
            ax.axhline(refs[f"OptimalNPofB{x}"].mean(), color=REFERENCE, linestyle=":", linewidth=1.2)
        ax.set_title(metric)
        ax.set_xticks(range(3), ["Random\nForest", "Naive\nBayes", "IBk"])
        ax.grid(axis="x", visible=False)
    ax = axes.flat[-1]
    ax.axis("off")
    ax.legend(handles=legend_handles() + [
        Line2D([0], [0], color=REFERENCE, linestyle="--", label="Prima le classi più piccole\n(senza modello)"),
        Line2D([0], [0], color=REFERENCE, linestyle=":", label="Ottimo"),
        Line2D([0], [0], color=TEXT, linewidth=1.6, label="Mediana")], loc="center", fontsize=9)
    fig.suptitle("10×10 CV: versioni migliori dei classificatori (configurazione scelta in ogni fold "
                 "sulla validation interna), 100 fold di test", fontsize=11, x=0.01, ha="left")
    fig.tight_layout()
    fig.savefig(out / "cv_migliori.png")
    plt.close(fig)


def fig_cv_configurations(folds, out):
    refs = folds.drop_duplicates(["Repetition", "Fold"])
    positions, labels, colors = [], [], []
    pos = 0
    for c in CLASSIFIERS:
        for fs in FEATURE_SELECTION:
            for b in BALANCING:
                positions.append(pos)
                labels.append(config_label(fs, b))
                colors.append(COLORS[c])
                pos += 1
        pos += 0.8
    for metric in METRICS:
        fig, ax = plt.subplots(figsize=(12, 4.2))
        for p, (c, fs, b), color in zip(positions, configurations(), colors):
            d = select(folds, c, fs, b)[metric].dropna()
            bp = ax.boxplot([d], positions=[p], widths=0.6, patch_artist=True)
            style_box(bp, color)
        if metric.startswith("NPofB"):
            x = metric[5:]
            ax.axhline(refs[f"SmallestFirstNPofB{x}"].mean(), color=REFERENCE, linestyle="--", linewidth=1,
                       label="Prima le classi più piccole (senza modello)")
            ax.axhline(refs[f"OptimalNPofB{x}"].mean(), color=REFERENCE, linestyle=":", linewidth=1.2,
                       label="Ottimo")
        ax.set_xticks(positions, labels, rotation=35, ha="right")
        ax.grid(axis="x", visible=False)
        ax.set_ylabel(metric)
        handles = legend_handles() + ([Line2D([0], [0], color=REFERENCE, linestyle="--",
                                              label="Prima le classi più piccole"),
                                       Line2D([0], [0], color=REFERENCE, linestyle=":", label="Ottimo")]
                                      if metric.startswith("NPofB") else [])
        ax.legend(handles=handles, ncol=len(handles), loc="upper left", bbox_to_anchor=(0, 1.13), fontsize=8.5)
        ax.set_title(f"10×10 CV: {metric} delle 18 configurazioni sui 100 fold di test", loc="left", pad=26)
        fig.tight_layout()
        fig.savefig(out / f"cv_configurazioni_{metric}.png")
        plt.close(fig)


def fig_holdout(intervals, test, refs, metrics, name, title):
    fig, axes = plt.subplots(1, len(metrics), figsize=(3.6 * len(metrics) + 1.6, 6.4), sharey=True)
    axes = np.atleast_1d(axes)
    ys, labels = [], []
    y = 0
    for c in CLASSIFIERS:
        for fs in FEATURE_SELECTION:
            for b in BALANCING:
                ys.append(y)
                labels.append(config_label(fs, b))
                y -= 1
        y -= 0.7
    chosen = {(r.Classifier, r.FeatureSelection, r.Balancing) for r in test.itertuples() if
              r.BestForClassifier == "yes"}
    for ax, metric in zip(axes, metrics):
        for yy, (c, fs, b) in zip(ys, configurations()):
            r = intervals[(intervals.Classifier == c) & (intervals.FeatureSelection == fs)
                          & (intervals.Balancing == b) & (intervals.Metric == metric)].iloc[0]
            ax.plot([r.Low, r.High], [yy, yy], color=COLORS[c], linewidth=1.6, solid_capstyle="round")
            is_chosen = (c, fs, b) in chosen
            ax.plot(r.Value, yy, marker="D" if is_chosen else "o", markersize=8 if is_chosen else 6,
                    markerfacecolor=COLORS[c] if is_chosen else "white", markeredgecolor=COLORS[c],
                    markeredgewidth=1.6, zorder=3)
        if metric.startswith("NPofB"):
            x = metric[5:]
            ax.axvline(refs[f"SmallestFirstNPofB{x}"], color=REFERENCE, linestyle="--", linewidth=1)
            ax.axvline(refs[f"OptimalNPofB{x}"], color=REFERENCE, linestyle=":", linewidth=1.2)
        ax.set_title(metric)
        ax.grid(axis="y", visible=False)
    axes[0].set_yticks(ys, labels)
    for c, start in zip(CLASSIFIERS, ys[::6]):
        axes[0].annotate(c, xy=(0, start + 0.75), xycoords=("axes fraction", "data"), xytext=(-8, 0),
                         textcoords="offset points", ha="right", va="center", fontweight="bold",
                         color=TEXT, annotation_clip=False)
    handles = legend_handles() + [
        Line2D([0], [0], marker="D", linestyle="", markersize=7, markerfacecolor=TEXT_2,
               markeredgecolor=TEXT_2, label="Scelta sul validation (release 8–9)"),
        Line2D([0], [0], color=TEXT_2, linewidth=1.6, label="IC bootstrap al 95%")]
    if any(m.startswith("NPofB") for m in metrics):
        handles += [Line2D([0], [0], color=REFERENCE, linestyle="--", label="Prima le classi più piccole"),
                    Line2D([0], [0], color=REFERENCE, linestyle=":", label="Ottimo")]
    fig.legend(handles=handles, loc="lower center", ncol=4, bbox_to_anchor=(0.5, -0.07), fontsize=8.5)
    fig.suptitle(title, fontsize=11, x=0.01, ha="left")
    fig.tight_layout()
    fig.savefig(name)
    plt.close(fig)


def fig_techniques(cv_tests, ho_tests, out):
    cmap = LinearSegmentedColormap.from_list("div", ["#a32a2b", "#ec4647", "#efefef", "#257de0", "#0f4a96"])
    norm = TwoSlopeNorm(vmin=-0.3, vcenter=0, vmax=0.3)
    rows = [(c, fs, b) for c in CLASSIFIERS for fs in FEATURE_SELECTION for b in BALANCING
            if not (fs == "NONE" and b == "NONE")]
    fig, axes = plt.subplots(1, 2, figsize=(13, 6.4), sharey=True)
    for ax, tests, title in ((axes[0], cv_tests, "10×10 CV (t-test corretto)"),
                             (axes[1], ho_tests, "Ordered holdout (bootstrap appaiato)")):
        t = tests[tests.Family == "TECHNIQUES"]
        grid = np.full((len(rows), len(METRICS)), np.nan)
        signif = np.zeros_like(grid, dtype=bool)
        for i, (c, fs, b) in enumerate(rows):
            model = f"{c}/{fs}/{b}"
            for j, metric in enumerate(METRICS):
                r = t[(t.ModelA == model) & (t.Metric == metric)]
                if len(r):
                    grid[i, j] = r.Difference.iloc[0]
                    signif[i, j] = r.Significant.iloc[0] == "yes"
        ax.imshow(grid, cmap=cmap, norm=norm, aspect="auto")
        for i in range(len(rows)):
            for j in range(len(METRICS)):
                v = grid[i, j]
                label = "0.000" if round(v, 3) == 0 else f"{v:+.3f}"
                ax.text(j, i, label + ("*" if signif[i, j] else ""), ha="center", va="center",
                        fontsize=8, fontweight="bold" if signif[i, j] else "normal",
                        color="white" if abs(v) > 0.12 else (TEXT if signif[i, j] else TEXT_2))
        for k in (5, 10):
            ax.axhline(k - 0.5, color="white", linewidth=3)
        ax.set_xticks(range(len(METRICS)), METRICS, rotation=30, ha="right")
        ax.set_title(title, loc="left")
        ax.grid(False)
        ax.spines[:].set_visible(False)
        ax.tick_params(length=0)
    axes[0].set_yticks(range(len(rows)), [f"{c} · {config_label(fs, b)}" for c, fs, b in rows])
    sm = plt.cm.ScalarMappable(cmap=cmap, norm=norm)
    cb = fig.colorbar(sm, ax=axes, fraction=0.025, pad=0.02)
    cb.set_label("Differenza rispetto alla baseline dello stesso classificatore")
    cb.outline.set_visible(False)
    fig.suptitle("Effetto di feature selection e balancing (configurazione − baseline). "
                 "* = significativo con correzione di Holm (α = 0,05)", fontsize=11, x=0.01, ha="left")
    fig.savefig(out / "effetto_tecniche.png")
    plt.close(fig)


def fig_cv_vs_holdout(summary, test, out):
    fig, axes = plt.subplots(1, 2, figsize=(10, 4.6))
    for ax, metric in zip(axes, ("Kappa", "NPofB20")):
        xs, ys = [], []
        for c, fs, b in configurations():
            x = summary[(summary.Classifier == c) & (summary.FeatureSelection == fs)
                        & (summary.Balancing == b)][f"{metric}_Mean"].iloc[0]
            y = select(test, c, fs, b)[metric].iloc[0]
            xs.append(x)
            ys.append(y)
            ax.plot(x, y, "o", markersize=7, markerfacecolor=COLORS[c], markeredgecolor="white",
                    markeredgewidth=1)
        lo = min(xs + ys) - 0.03
        hi = max(xs + ys) + 0.03
        ax.plot([lo, hi], [lo, hi], color=REFERENCE, linestyle="--", linewidth=1)
        ax.annotate("CV = holdout", xy=(lo + 0.62 * (hi - lo), lo + 0.62 * (hi - lo)), xytext=(6, -6),
                    textcoords="offset points", ha="left", va="top", rotation=45, rotation_mode="anchor",
                    color=TEXT_2, fontsize=8)
        rho = pd.Series(xs).corr(pd.Series(ys), method="spearman")
        ax.set_title(f"{metric}   (Spearman ρ = {rho:.2f})", loc="left")
        ax.set_xlabel("Media sui 100 fold della CV")
        ax.set_ylabel("Test dell'holdout (release 10–11)")
        ax.set_xlim(lo, hi)
        ax.set_ylim(lo, hi)
        ax.set_aspect("equal")
    axes[1].legend(handles=legend_handles(), loc="lower right")
    fig.suptitle("Le 18 configurazioni: CV contro ordered holdout", fontsize=11, x=0.01, ha="left")
    fig.tight_layout()
    fig.savefig(out / "cv_vs_holdout.png")
    plt.close(fig)


def fig_selection(selection, test, out):
    best = selection[selection.Kind == "BEST"].copy()
    best = best.groupby(["Classifier", "FeatureSelection", "Balancing"], as_index=False).Count.sum()
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.4), sharex=True)
    for ax, c in zip(axes, CLASSIFIERS):
        labels, counts, chosen = [], [], []
        for fs in FEATURE_SELECTION:
            for b in BALANCING:
                r = best[(best.Classifier == c) & (best.FeatureSelection == fs) & (best.Balancing == b)]
                labels.append(config_label(fs, b))
                counts.append(int(r.Count.sum()) if len(r) else 0)
                chosen.append(select(test, c, fs, b).BestForClassifier.iloc[0] == "yes")
        y = np.arange(len(labels))[::-1]
        ax.barh(y, counts, color=COLORS[c], height=0.62, edgecolor="white", linewidth=2)
        for yy, n, ch in zip(y, counts, chosen):
            ax.text(n + 1.5, yy, f"{n}" + ("   ◆ holdout" if ch else ""), va="center", fontsize=8.5,
                    color=TEXT if ch else TEXT_2)
        ax.set_yticks(y, labels)
        ax.set_xlim(0, 118)
        ax.set_title(c, loc="left", color=TEXT)
        ax.grid(axis="y", visible=False)
        ax.set_xlabel("Fold in cui è stata scelta (su 100)")
    fig.suptitle("Stabilità della selezione: configurazione scelta per ogni classificatore nei 100 fold della "
                 "CV (◆ = scelta sul validation dell'holdout)", fontsize=11, x=0.01, ha="left")
    fig.tight_layout()
    fig.savefig(out / "selezione_cv.png")
    plt.close(fig)


def per_release(pred, names):
    rows = []
    for rel, gr in pred.groupby("ReleaseID"):
        ref = {}
        for c, fs, b in configurations():
            g = select(gr, c, fs, b)
            m = PredictionMetrics(g).compute(np.ones((1, len(g))))
            rows.append(dict(ReleaseID=rel, Version=names.get(rel, ""), Model=f"{c}/{fs}/{b}", Classifier=c,
                             FeatureSelection=fs, Balancing=b, Rows=len(g), Buggy=int((g.Actual == "yes").sum()),
                             **{k: m[k][0] for k in METRICS}))
            if not ref:
                ref = {f"NPofB{x}": smallest_first(g, x) for x in (10, 20)}
        rows.append(dict(ReleaseID=rel, Version=names.get(rel, ""), Model="SmallestFirst", Classifier="-",
                         FeatureSelection="-", Balancing="-", Rows=len(g), Buggy=int((g.Actual == "yes").sum()),
                         **ref))
    return pd.DataFrame(rows)


def fig_per_release(table, test, out):
    releases = sorted(table.ReleaseID.unique())
    names = {r: (table[table.ReleaseID == r].Version.iloc[0] or f"Release {r}") for r in releases}
    fig, axes = plt.subplots(1, 2, figsize=(9.5, 4.2))
    for ax, metric in zip(axes, ("Kappa", "NPofB20")):
        for c in CLASSIFIERS:
            best = test[(test.Classifier == c) & (test.BestForClassifier == "yes")].iloc[0]
            model = f"{c}/{best.FeatureSelection}/{best.Balancing}"
            v = [table[(table.ReleaseID == r) & (table.Model == model)][metric].iloc[0] for r in releases]
            ax.plot(range(len(releases)), v, "-o", color=COLORS[c], linewidth=2, markersize=7,
                    markeredgecolor="white", markeredgewidth=1)
            ax.annotate(f"{c}  {config_label(best.FeatureSelection, best.Balancing)}", xy=(len(releases) - 1,
                                                                                           v[-1]), xytext=(8, 0), textcoords="offset points", va="center", fontsize=8, color=TEXT)
        if metric == "NPofB20":
            v = [table[(table.ReleaseID == r) & (table.Model == "SmallestFirst")][metric].iloc[0] for r in releases]
            ax.plot(range(len(releases)), v, "--", color=REFERENCE, linewidth=1.2)
            ax.annotate("Prima le classi più piccole", xy=(len(releases) - 1, v[-1]), xytext=(8, 0),
                        textcoords="offset points", va="center", fontsize=8, color=TEXT_2)
        ax.set_xticks(range(len(releases)), [f"{names[r]}\n(ReleaseID {r})" for r in releases])
        ax.set_xlim(-0.3, len(releases) - 1 + 1.4)
        ax.set_title(metric, loc="left")
        ax.grid(axis="x", visible=False)
    fig.suptitle("Holdout: versioni migliori dei classificatori su ciascuna release di test", fontsize=11, x=0.01,
                 ha="left")
    fig.tight_layout()
    fig.savefig(out / "holdout_per_release.png")
    plt.close(fig)


# ---------------------------------------------------------------- main

def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("output")
    plots = out / "plots"
    plots.mkdir(parents=True, exist_ok=True)

    folds = read(out, "ml_cv_folds.csv")
    summary = read(out, "ml_cv_summary.csv")
    selection = read(out, "ml_cv_selection.csv")
    cv_tests = read(out, "ml_cv_tests.csv")
    test = read(out, "ml_holdout_test.csv")
    ho_tests = read(out, "ml_holdout_tests.csv")
    pred = read(out, "ml_holdout_predictions.csv")

    fig_cv_best(folds, plots)
    fig_cv_configurations(folds, plots)

    intervals = holdout_intervals(pred)
    check_against_java(intervals, test)
    refs = test.iloc[0]
    fig_holdout(intervals, test, refs, ["Kappa", "AUC", "NPofB20"], plots / "holdout_test.png",
                "Ordered holdout: training release 1–9, test release 10–11")
    fig_holdout(intervals, test, refs, ["Accuracy", "Precision", "Recall", "NPofB10"],
                plots / "holdout_test_altre.png", "Ordered holdout: altre metriche")

    fig_techniques(cv_tests, ho_tests, plots)
    fig_cv_vs_holdout(summary, test, plots)
    fig_selection(selection, test, plots)

    table = per_release(pred, release_names(out))
    table.to_csv(plots / "holdout_per_release.csv", index=False, float_format="%.6f")
    fig_per_release(table, test, plots)
    print(f"Grafici scritti in {plots.resolve()}")


if __name__ == "__main__":
    main()
