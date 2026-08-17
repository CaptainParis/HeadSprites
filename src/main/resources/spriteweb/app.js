"use strict";

var BLOCK = 8;
var GRID_H = 8;
var blocks = 1;
var CELL = 32;
var pixels = newGrid();
var token = "";
var seqTokens = [];
var lastImage = null;
var mode = "sequence";
var previewTimer = null;
var previewFrame = 0;
var frames = [];
var curFrame = 0;

function blankFrame() {
    var g = [];
    var n = gridW() * GRID_H;
    for (var i = 0; i < n; i++) g.push(null);
    return g;
}

function commitFrame() {
    if (mode === "animation" && frames.length > 0) {
        frames[curFrame] = pixels.slice();
    }
}

function loadFrame(index) {
    curFrame = ((index % frames.length) + frames.length) % frames.length;
    pixels = frames[curFrame].slice();
    updateFrameNav();
    drawGrid();
}

function updateFrameNav() {
    var counter = el("frame-counter");
    if (counter) {
        counter.innerHTML = "Frame <b>" + (curFrame + 1) + "</b> / <b>" + frames.length + "</b>";
    }
    var del = el("frame-del");
    if (del) del.disabled = frames.length <= 1;
}

function gridW() { return BLOCK * blocks; }

function newGrid() {
    var g = [];
    var n = gridW() * GRID_H;
    for (var i = 0; i < n; i++) g.push(null);
    return g;
}

var canvas = document.getElementById("grid");
var ctx = canvas.getContext("2d");

function updateRes() {
    var out = document.getElementById("resout");
    if (!out) return;
    out.innerHTML = "<b>" + gridW() + "</b> \u00d7 <b>" + GRID_H + "</b> px";
}

function setupCanvas() {
    var w = gridW();
    CELL = Math.max(6, Math.floor(512 / w));
    canvas.width = w * CELL;
    canvas.height = GRID_H * CELL;
    updateRes();
}

function drawGrid() {
    var w = gridW();
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    for (var y = 0; y < GRID_H; y++) {
        for (var x = 0; x < w; x++) {
            var c = pixels[y * w + x];
            if (c) {
                ctx.fillStyle = c;
                ctx.fillRect(x * CELL, y * CELL, CELL, CELL);
            }
        }
    }
    ctx.strokeStyle = "rgba(255,255,255,0.06)";
    ctx.lineWidth = 1;
    for (var i = 0; i <= w; i++) {
        ctx.beginPath();
        ctx.moveTo(i * CELL, 0); ctx.lineTo(i * CELL, canvas.height); ctx.stroke();
    }
    for (var j = 0; j <= GRID_H; j++) {
        ctx.beginPath();
        ctx.moveTo(0, j * CELL); ctx.lineTo(canvas.width, j * CELL); ctx.stroke();
    }
    if (blocks > 1) {
        ctx.strokeStyle = "rgba(91,140,255,0.55)";
        ctx.lineWidth = 2;
        for (var b = 1; b < blocks; b++) {
            var bx = b * BLOCK * CELL;
            ctx.beginPath();
            ctx.moveTo(bx, 0); ctx.lineTo(bx, canvas.height); ctx.stroke();
        }
    }
    if (mode === "animation" && !previewTimer) {
        drawPreview(curFrame);
    }
}

function cellFromEvent(e) {
    var r = canvas.getBoundingClientRect();
    var w = gridW();
    var x = Math.floor((e.clientX - r.left) / (r.width / w));
    var y = Math.floor((e.clientY - r.top) / (r.height / GRID_H));
    if (x < 0 || y < 0 || x >= w || y >= GRID_H) return -1;
    return y * w + x;
}

var tool = "brush";
var painting = false;
var recentColors = [];

function setTool(name) {
    tool = name;
    ["brush", "eraser", "fill", "pick"].forEach(function (t) {
        var b = el("tool-" + t);
        if (b) b.classList.toggle("active", t === name);
    });
    canvas.style.cursor = (name === "pick") ? "copy" : "crosshair";
}
["brush", "eraser", "fill", "pick"].forEach(function (t) {
    var b = el("tool-" + t);
    if (b) b.addEventListener("click", function () { setTool(t); });
});

function rememberColor(hex) {
    if (!hex) return;
    var i = recentColors.indexOf(hex);
    if (i !== -1) recentColors.splice(i, 1);
    recentColors.unshift(hex);
    if (recentColors.length > 12) recentColors.length = 12;
    renderSwatches();
}
function renderSwatches() {
    var box = el("swatches");
    if (!box) return;
    box.innerHTML = "";
    recentColors.forEach(function (hex) {
        var s = document.createElement("button");
        s.type = "button"; s.className = "swatch"; s.title = hex;
        s.style.background = hex;
        s.addEventListener("click", function () { el("color").value = hex; setTool("brush"); });
        box.appendChild(s);
    });
}

function floodFill(idx, fillColor) {
    var w = gridW();
    var target = pixels[idx];
    if (target === fillColor) return;
    var stack = [idx];
    while (stack.length) {
        var i = stack.pop();
        if (pixels[i] !== target) continue;
        pixels[i] = fillColor;
        var x = i % w, y = Math.floor(i / w);
        if (x > 0) stack.push(i - 1);
        if (x < w - 1) stack.push(i + 1);
        if (y > 0) stack.push(i - w);
        if (y < GRID_H - 1) stack.push(i + w);
    }
}

function applyTool(idx, erase) {
    var color = el("color").value;
    if (erase || tool === "eraser") { pixels[idx] = null; return; }
    if (tool === "pick") {
        if (pixels[idx]) { el("color").value = pixels[idx]; rememberColor(pixels[idx]); }
        setTool("brush");
        return;
    }
    if (tool === "fill") { floodFill(idx, color); rememberColor(color); return; }
    pixels[idx] = color;
    rememberColor(color);
}

canvas.addEventListener("contextmenu", function (e) { e.preventDefault(); });
canvas.addEventListener("mousedown", function (e) {
    var idx = cellFromEvent(e);
    if (idx < 0) return;
    var erase = (e.button === 2);
    painting = !erase && (tool === "brush" || tool === "eraser");
    applyTool(idx, erase);
    drawGrid();
});
canvas.addEventListener("mousemove", function (e) {
    if (!painting) return;
    var idx = cellFromEvent(e);
    if (idx >= 0) { applyTool(idx, false); drawGrid(); }
});
window.addEventListener("mouseup", function () { painting = false; });

document.getElementById("clear").addEventListener("click", function () {
    pixels = newGrid(); drawGrid();
});

function resizeGrid(old, oldW, w) {
    var ng = [];
    var n = w * GRID_H;
    for (var i = 0; i < n; i++) ng.push(null);
    var copyW = Math.min(oldW, w);
    for (var y = 0; y < GRID_H; y++) {
        for (var x = 0; x < copyW; x++) {
            ng[y * w + x] = old[y * oldW + x];
        }
    }
    return ng;
}

function setBlocks(n) {
    if (n === blocks) return;
    var oldW = gridW();
    if (mode === "animation" && frames.length > 0) {
        commitFrame();
        blocks = n;
        setupCanvas();
        var w = gridW();
        for (var i = 0; i < frames.length; i++) {
            frames[i] = resizeGrid(frames[i], oldW, w);
        }
        pixels = frames[curFrame].slice();
        drawGrid();
        syncLayoutControls();
        return;
    }
    var old = pixels;
    blocks = n;
    setupCanvas();
    pixels = resizeGrid(old, oldW, gridW());
    drawGrid();
    syncLayoutControls();
}

function syncLayoutControls() {
    var input = el("head-count");
    if (input && parseInt(input.value, 10) !== blocks) input.value = blocks;
}

el("head-count").addEventListener("change", function () {
    var n = parseInt(el("head-count").value, 10);
    if (isNaN(n) || n < 1) n = 1;
    el("head-count").value = n;
    if (n > 16) log("Heads set to " + n + " \u2014 that is a lot; each head is a separate MineSkin generation.", "err");
    setBlocks(n);
});

document.getElementById("upload").addEventListener("change", function (e) {
    var file = e.target.files[0];
    if (!file) return;
    if (file.type === "image/gif") { loadGifFrames(file); return; }
    var img = new Image();
    img.onload = function () { lastImage = img; fitImage(img); };
    img.src = URL.createObjectURL(file);
});

function loadGifFrames(file) {
    if (typeof ImageDecoder === "undefined") {
        log("This browser cannot split GIF frames. Use a Chromium-based browser, or draw the frames by hand.", "err");
        return;
    }
    log("Reading GIF frames...", "");
    file.arrayBuffer().then(function (buf) {
        var dec = new ImageDecoder({ data: buf, type: file.type || "image/gif" });
        return dec.tracks.ready.then(function () {
            var track = dec.tracks.selectedTrack;
            var count = (track && track.frameCount) ? track.frameCount : 1;
            var max = 16;
            if (count > max) {
                log("GIF has " + count + " frames; using the first " + max + ".", "err");
                count = max;
            }
            var frames = [];
            var chain = Promise.resolve();
            for (var i = 0; i < count; i++) {
                (function (idx) {
                    chain = chain.then(function () {
                        return dec.decode({ frameIndex: idx }).then(function (res) { frames.push(res.image); });
                    });
                })(i);
            }
            return chain.then(function () { paintFramesFromImages(frames); });
        });
    }).catch(function (e) { log("GIF decode failed: " + e.message, "err"); });
}

function paintFramesFromImages(images) {
    var n = images.length;
    if (n < 1) return;
    if (mode !== "animation") { mode = "animation"; }
    blocks = 1;
    setupCanvas();
    var w = gridW();
    frames = [];
    var tmp = document.createElement("canvas");
    tmp.width = w; tmp.height = GRID_H;
    var tctx = tmp.getContext("2d");
    tctx.imageSmoothingEnabled = false;
    for (var b = 0; b < n; b++) {
        var g = blankFrame();
        tctx.clearRect(0, 0, w, GRID_H);
        try { tctx.drawImage(images[b], 0, 0, w, GRID_H); } catch (e) { /* skip */ }
        var data = tctx.getImageData(0, 0, w, GRID_H).data;
        for (var i = 0; i < w * GRID_H; i++) {
            var a = data[i * 4 + 3];
            g[i] = a < 24 ? null : rgbHex(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]);
        }
        frames.push(g);
        if (images[b] && images[b].close) { try { images[b].close(); } catch (e) { /* ignore */ } }
    }
    curFrame = 0;
    setMode("animation");
    syncLayoutControls();
    log("Loaded " + n + " frames from GIF. Page through them, then Generate & Save.", "ok");
}
document.getElementById("fit").addEventListener("click", function () {
    if (!lastImage) { log("Upload an image first, then Fit scales it across the grid.", ""); return; }
    fitImage(lastImage);
});

window.addEventListener("paste", function (e) {
    var items = (e.clipboardData && e.clipboardData.items) || [];
    for (var i = 0; i < items.length; i++) {
        if (items[i].type.indexOf("image") !== 0) continue;
        var file = items[i].getAsFile();
        if (!file) continue;
        var img = new Image();
        img.onload = function () { lastImage = img; fitImage(img); };
        img.src = URL.createObjectURL(file);
        e.preventDefault();
        return;
    }
});

function fitImage(img) {
    var w = gridW();
    var tmp = document.createElement("canvas");
    tmp.width = w; tmp.height = GRID_H;
    var tctx = tmp.getContext("2d");
    tctx.imageSmoothingEnabled = false;
    tctx.clearRect(0, 0, w, GRID_H);
    tctx.drawImage(img, 0, 0, w, GRID_H);
    var data = tctx.getImageData(0, 0, w, GRID_H).data;
    pixels = newGrid();
    for (var i = 0; i < w * GRID_H; i++) {
        var a = data[i * 4 + 3];
        if (a < 24) { pixels[i] = null; continue; }
        pixels[i] = rgbHex(data[i * 4], data[i * 4 + 1], data[i * 4 + 2]);
    }
    drawGrid();
    log("Fitted image across " + w + "x" + GRID_H + ".", "ok");
}

function rgbHex(r, g, b) {
    return "#" + [r, g, b].map(function (v) {
        var h = v.toString(16); return h.length === 1 ? "0" + h : h;
    }).join("");
}

function setMode(m) {
    var wasAnim = (mode === "animation");
    mode = m;
    var s = el("mode-sequence"), a = el("mode-animation");
    if (s) s.classList.toggle("active", m === "sequence");
    if (a) a.classList.toggle("active", m === "animation");
    var isAnim = (m === "animation");
    var label = el("count-label");
    if (label) label.textContent = "Heads";
    var animOnly = document.querySelectorAll(".anim-only");
    for (var i = 0; i < animOnly.length; i++) {
        animOnly[i].style.display = isAnim ? "" : "none";
    }
    if (isAnim) {
        if (frames.length === 0) {
            frames = [pixels.slice()];
            curFrame = 0;
        }
        pixels = frames[curFrame].slice();
        updateFrameNav();
        drawGrid();
        drawPreview(0);
    } else {
        stopPreview();
        if (wasAnim) {
            drawGrid();
        }
    }
}

function addFrame() {
    commitFrame();
    frames.splice(curFrame + 1, 0, blankFrame());
    loadFrame(curFrame + 1);
    log("Added frame " + (curFrame + 1) + ".", "");
}

function deleteFrame() {
    if (frames.length <= 1) { log("An animation needs at least one frame.", "err"); return; }
    frames.splice(curFrame, 1);
    if (curFrame >= frames.length) curFrame = frames.length - 1;
    pixels = frames[curFrame].slice();
    updateFrameNav();
    drawGrid();
}

function stepFrame(delta) {
    if (frames.length < 2) return;
    commitFrame();
    loadFrame(curFrame + delta);
}

function fpsValue() {
    var f = parseInt(el("fps").value, 10);
    if (isNaN(f) || f < 1) f = 8;
    if (f > 30) f = 30;
    return f;
}

function intervalMs() { return Math.max(30, Math.round(1000 / fpsValue())); }

function drawPreview(frame) {
    var pc = el("preview");
    if (!pc) return;
    var pctx = pc.getContext("2d");
    var w = gridW();
    pctx.clearRect(0, 0, pc.width, pc.height);
    if (frames.length === 0) return;
    var f = ((frame % frames.length) + frames.length) % frames.length;
    var src = (mode === "animation" && f === curFrame) ? pixels : frames[f];
    if (!src) return;
    var cell = Math.max(1, Math.floor(Math.min(pc.width / w, pc.height / GRID_H)));
    for (var y = 0; y < GRID_H; y++) {
        for (var x = 0; x < w; x++) {
            var c = src[y * w + x];
            if (!c) continue;
            pctx.fillStyle = c;
            pctx.fillRect(x * cell, y * cell, cell, cell);
        }
    }
}

function startPreview() {
    stopPreview();
    previewFrame = 0;
    drawPreview(0);
    previewTimer = setInterval(function () {
        previewFrame++;
        drawPreview(previewFrame);
    }, intervalMs());
    var b = el("preview-play");
    if (b) b.textContent = "Stop";
}

function stopPreview() {
    if (previewTimer) { clearInterval(previewTimer); previewTimer = null; }
    var b = el("preview-play");
    if (b) b.textContent = "Play";
}

function exportBlockFrom(grid, w, b) {
    var out = document.createElement("canvas");
    out.width = BLOCK; out.height = BLOCK;
    var octx = out.getContext("2d");
    var img = octx.createImageData(BLOCK, BLOCK);
    for (var y = 0; y < BLOCK; y++) {
        for (var x = 0; x < BLOCK; x++) {
            var c = grid[y * w + (b * BLOCK + x)];
            var di = (y * BLOCK + x) * 4;
            if (!c) { img.data[di + 3] = 0; continue; }
            img.data[di] = parseInt(c.substr(1, 2), 16);
            img.data[di + 1] = parseInt(c.substr(3, 2), 16);
            img.data[di + 2] = parseInt(c.substr(5, 2), 16);
            img.data[di + 3] = 255;
        }
    }
    octx.putImageData(img, 0, 0);
    return out.toDataURL("image/png");
}

function exportBlock(b) {
    return exportBlockFrom(pixels, gridW(), b);
}

function el(id) { return document.getElementById(id); }

function log(msg, cls) {
    var el = document.getElementById("log");
    el.textContent = msg;
    el.className = "log " + (cls || "");
}

function api(path, method, body) {
    return fetch(path + "?token=" + encodeURIComponent(token), {
        method: method || "GET",
        headers: { "Content-Type": "application/json", "X-Sprite-Token": token },
        body: body ? JSON.stringify(body) : undefined
    }).then(function (res) {
        return res.json().then(function (json) {
            if (!res.ok) throw new Error(json.error || ("HTTP " + res.status));
            return json;
        });
    });
}

function setStatus(online) {
    var s = document.getElementById("status");
    s.textContent = online ? "Online" : "Offline";
    s.className = "status " + (online ? "online" : "");
}

document.getElementById("connect").addEventListener("click", function () {
    token = document.getElementById("token").value.trim();
    api("/api/state").then(function (state) {
        setStatus(true);
        render(state);
        log("Connected." + (state.apiKeySet ? "" : " WARNING: no MineSkin API key set."),
            state.apiKeySet ? "ok" : "err");
    }).catch(function (e) {
        setStatus(false);
        log("Connect failed: " + e.message, "err");
    });
});

function generateSequential(items, index, lastState) {
    if (index >= items.length) return Promise.resolve(lastState);
    var it = items[index];
    log("Generating '" + it.name + "' (" + (index + 1) + "/" + items.length + ") via MineSkin...", "");
    return api("/api/generate", "POST", {
        name: it.name,
        image: it.image,
        fallback: it.fallback
    }).then(function (state) {
        return generateSequential(items, index + 1, state);
    });
}

document.getElementById("generate").addEventListener("click", function () {
    var raw = document.getElementById("name").value.trim();
    if (!raw) { log("Enter a sprite name.", "err"); return; }
    var base = raw.toLowerCase();
    var fallback = document.getElementById("fallback").value;

    if (mode === "animation") {
        commitFrame();
        var w = gridW();
        var frameRows = [];
        var frameItems = [];
        for (var f = 0; f < frames.length; f++) {
            var row = [];
            for (var h = 0; h < blocks; h++) {
                var headName = base + "_f" + (f + 1) + (blocks > 1 ? "_h" + (h + 1) : "");
                row.push(headName);
                frameItems.push({
                    name: headName,
                    image: exportBlockFrom(frames[f], w, h),
                    fallback: (f === 0 && h === 0) ? fallback : ""
                });
            }
            frameRows.push(row);
        }
        var iv = intervalMs();
        log("Generating " + frameItems.length + " head(s) across " + frames.length
            + " frame(s) via MineSkin (may take a while)...", "");
        generateSequential(frameItems, 0, null).then(function () {
            return api("/api/animation", "POST", { name: base, frames: frameRows, intervalMs: iv });
        }).then(function (state) {
            render(state);
            log("Saved animation with " + frames.length + " frame(s). Use <anim:" + base + ">.", "ok");
        }).catch(function (e) { log("Generate failed: " + e.message, "err"); });
        return;
    }

    if (blocks === 1) {
        var single = [{ name: base, image: exportBlock(0), fallback: fallback }];
        generateSequential(single, 0, null).then(function (state) {
            render(state);
            log("Saved sprite. Use <head:" + base + ">.", "ok");
        }).catch(function (e) { log("Generate failed: " + e.message, "err"); });
        return;
    }

    var items = [];
    var heads = [];
    for (var b = 0; b < blocks; b++) {
        var partName = base + "_" + (b + 1);
        heads.push(partName);
        items.push({ name: partName, image: exportBlock(b), fallback: b === 0 ? fallback : "" });
    }
    log("Generating " + blocks + " sprites via MineSkin (may take a while)...", "");
    generateSequential(items, 0, null).then(function () {
        return api("/api/sequence", "POST", { name: base, heads: heads });
    }).then(function (state) {
        render(state);
        document.getElementById("seq-name").value = base;
        log("Saved " + blocks + " sprites + sequence. Use <seq:" + base + ">.", "ok");
    }).catch(function (e) { log("Generate failed: " + e.message, "err"); });
});

function render(state) {
    var gallery = document.getElementById("gallery");
    gallery.innerHTML = "";
    (state.sprites || []).forEach(function (sp) {
        var chip = document.createElement("div");
        chip.className = "sprite-chip";
        chip.title = "click to add to sequence";
        var n = document.createElement("div"); n.textContent = sp.name; chip.appendChild(n);
        if (sp.fallback) {
            var fb = document.createElement("div"); fb.className = "fb";
            fb.textContent = sp.fallback; chip.appendChild(fb);
        }
        chip.addEventListener("click", function () { addSeqToken(sp.name); });
        gallery.appendChild(chip);
    });

    var list = document.getElementById("seq-list");
    list.innerHTML = "";
    var seqs = state.sequences || {};
    Object.keys(seqs).forEach(function (name) {
        var heads = seqs[name];
        var chip = document.createElement("div");
        chip.className = "seq-chip";
        var n = document.createElement("div"); n.textContent = "<seq:" + name + ">"; chip.appendChild(n);
        var mm = document.createElement("div"); mm.className = "mm";
        mm.textContent = heads.map(function (h) { return "<head:" + h + ">"; }).join("");
        chip.appendChild(mm);
        var del = document.createElement("button");
        del.className = "del"; del.textContent = "DELETE";
        del.addEventListener("click", function () {
            api("/api/sequence/delete", "POST", { name: name }).then(render);
        });
        chip.appendChild(del);
        list.appendChild(chip);
    });

    var animList = document.getElementById("anim-list");
    if (animList) {
        animList.innerHTML = "";
        var anims = state.animations || {};
        Object.keys(anims).forEach(function (name) {
            var info = anims[name];
            var chip = document.createElement("div");
            chip.className = "seq-chip";
            var n = document.createElement("div"); n.textContent = "<anim:" + name + ">"; chip.appendChild(n);
            var meta = document.createElement("div"); meta.className = "mm";
            var width = (info.frames[0] && info.frames[0].length) ? info.frames[0].length : 1;
            meta.textContent = info.frames.length + " frames \u00d7 " + width
                + " head" + (width === 1 ? "" : "s") + " @ " + info.intervalMs + "ms";
            chip.appendChild(meta);
            var del = document.createElement("button");
            del.className = "del"; del.textContent = "DELETE";
            del.addEventListener("click", function () {
                api("/api/animation/delete", "POST", { name: name }).then(render);
            });
            chip.appendChild(del);
            animList.appendChild(chip);
        });
    }
}

function addSeqToken(name) {
    seqTokens.push(name);
    renderSeqCurrent();
}
function renderSeqCurrent() {
    var el = document.getElementById("seq-current");
    el.innerHTML = "";
    seqTokens.forEach(function (t) {
        var tok = document.createElement("span");
        tok.className = "tok"; tok.textContent = t;
        el.appendChild(tok);
    });
}
document.getElementById("seq-clear").addEventListener("click", function () {
    seqTokens = []; renderSeqCurrent();
});
document.getElementById("seq-save").addEventListener("click", function () {
    var name = document.getElementById("seq-name").value.trim();
    if (!name) { log("Enter a sequence name.", "err"); return; }
    if (seqTokens.length === 0) { log("Add sprites to the sequence first.", "err"); return; }
    api("/api/sequence", "POST", { name: name, heads: seqTokens }).then(function (state) {
        render(state);
        log("Saved sequence '" + name + "'. Use <seq:" + name.toLowerCase() + ">.", "ok");
    }).catch(function (e) { log("Save failed: " + e.message, "err"); });
});

["sequence", "animation"].forEach(function (m) {
    var b = el("mode-" + m);
    if (b) b.addEventListener("click", function () { setMode(m); });
});
(function () {
    var play = el("preview-play");
    if (play) play.addEventListener("click", function () {
        if (previewTimer) { stopPreview(); } else { startPreview(); }
    });
    var fps = el("fps");
    if (fps) fps.addEventListener("change", function () {
        if (previewTimer) { startPreview(); }
    });
    var prev = el("frame-prev");
    if (prev) prev.addEventListener("click", function () { stepFrame(-1); });
    var next = el("frame-next");
    if (next) next.addEventListener("click", function () { stepFrame(1); });
    var add = el("frame-add");
    if (add) add.addEventListener("click", addFrame);
    var del = el("frame-del");
    if (del) del.addEventListener("click", deleteFrame);
})();

window.addEventListener("keydown", function (e) {
    if (mode !== "animation") return;
    var t = e.target;
    if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA")) return;
    if (e.key === "ArrowLeft") { stepFrame(-1); e.preventDefault(); }
    else if (e.key === "ArrowRight") { stepFrame(1); e.preventDefault(); }
});

setupCanvas();
drawGrid();
setTool("brush");
setMode("sequence");
renderSwatches();
