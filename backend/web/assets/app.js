;(() => {
	const API =
		window.location.origin.includes('127.0.0.1') ||
		window.location.origin.includes('localhost')
			? 'http://127.0.0.1:5000'
			: window.location.origin

	// === Aurora Background: soft blobs, hue drift, slow spin, mouse attraction ===
	// === Aurora Background: soft blobs, hue drift, slow spin, strong cursor follow ===
	function initAnimatedBackground() {
		if (
			window.matchMedia &&
			window.matchMedia('(prefers-reduced-motion: reduce)').matches
		)
			return

		// ---- твои настройки + два новых ----
		const BLOB_COUNT = 2 // сколько «пятен»
		const BASE_SPEED = 0.00008 // базовая угл. скорость (рад/мс)
		const SPEED_JITTER = 0.0005 // разброс скорости
		const SCALE_MIN = 2 // минимальный масштаб пятна
		const SCALE_MAX = 3 // максимальный масштаб пятна
		const PULSE_SPEED = 0.000003 // скорость «дыхания»
		const HUE_DRIFT = 8 // градусов hue/сек (перелив)
		const ATTR_RADIUS_K = 0.5 // радиус «магнита» к курсору (в долях меньшей стороны)
		const ATTR_FORCE = 0.45 // сила притяжения
		const FOLLOW_EASE = 0.42 // сглаживание следования курсора
		const DPR_CAP = 2

		// новое:
		const RING_INNER_K = 0.1 // прозрачный центр = rOuter * 0.125 (в 2x меньше)
		const CURSOR_PULL = 0.6 // якорение центра пятна к курсору (0..1)

		// ---- canvas ----
		let canvas = document.getElementById('bg-canvas')
		if (!canvas) {
			canvas = document.createElement('canvas')
			canvas.id = 'bg-canvas'
			document.body.prepend(canvas)
		}
		const ctx = canvas.getContext('2d', { alpha: true })

		let w, h, dpr, cx, cy, minSide
		const clamp = (n, a, b) => Math.max(a, Math.min(b, n))
		function resize() {
			dpr = clamp(window.devicePixelRatio || 1, 1, DPR_CAP)
			w = window.innerWidth
			h = window.innerHeight
			canvas.width = Math.floor(w * dpr)
			canvas.height = Math.floor(h * dpr)
			canvas.style.width = w + 'px'
			canvas.style.height = h + 'px'
			cx = canvas.width / 2
			cy = canvas.height / 2
			minSide = Math.min(canvas.width, canvas.height)
		}
		resize()
		window.addEventListener('resize', resize)

		// базовые тона под темы (дальше hue будет «плыть»)
		const baseDark = [264, 190] // фиолет/бирюза
		const baseLight = [248, 196]
		const isLight = () => document.documentElement.dataset.theme === 'light'
		let baseHues = (isLight() ? baseLight : baseDark).slice()
		new MutationObserver(() => {
			baseHues = (isLight() ? baseLight : baseDark).slice()
		}).observe(document.documentElement, {
			attributes: true,
			attributeFilter: ['data-theme'],
		})

		// утилиты
		const TWO = Math.PI * 2
		const rnd = (a, b) => a + Math.random() * (b - a)
		const hsl = (h, s, l, a = 1) => `hsla(${h},${s}%,${l}%,${a})`

		// положение курсора (в координатах canvas) и сглаженная позиция
		let pointer = { x: cx, y: cy },
			mouse = { x: cx, y: cy }
		window.addEventListener('pointermove', e => {
			pointer.x = e.clientX * dpr
			pointer.y = e.clientY * dpr
		})
		window.addEventListener('pointerleave', () => {
			pointer.x = cx
			pointer.y = cy
		})

		// заготовки «пятен»
		const blobs = new Array(BLOB_COUNT).fill(0).map((_, i) => {
			const orbitR = rnd(0.25, 0.5) * minSide // базовый радиус орбиты
			const angle = rnd(0, TWO)
			const speed =
				(BASE_SPEED + rnd(-SPEED_JITTER, SPEED_JITTER)) *
				(Math.random() < 0.5 ? 1 : -1)
			const aX = 1 + (i % 3),
				aY = 2 + ((i + 1) % 3) // Лиссажу по XY
			const phase = rnd(0, TWO)
			const sx = rnd(1.3, 1.8),
				sy = rnd(1.3, 1.8) // эллиптичность
			const hue0 = baseHues[i % baseHues.length]
			return { orbitR, angle, speed, aX, aY, phase, sx, sy, hue0 }
		})

		// кадр
		let last = performance.now(),
			rafId
		function frame(t) {
			const dt = clamp(t - last, 0, 34)
			last = t

			// мягкое следование
			mouse.x += (pointer.x - mouse.x) * FOLLOW_EASE
			mouse.y += (pointer.y - mouse.y) * FOLLOW_EASE

			ctx.setTransform(1, 0, 0, 1, 0, 0)
			ctx.clearRect(0, 0, canvas.width, canvas.height)

			// аддитивное смешивание — мягкие переливы
			ctx.globalCompositeOperation = 'lighter'

			for (const [i, b] of blobs.entries()) {
				b.angle += b.speed * dt

				// лёгкая фигура Лиссажу (дает «жизнь»)
				const lX = Math.sin(b.aX * (t * 0.0002) + b.phase)
				const lY = Math.sin(b.aY * (t * 0.00018) + b.phase + 1.2)

				// базовая траектория
				const baseX =
					cx + Math.cos(b.angle) * b.orbitR * 0.35 + lX * b.orbitR * 0.25
				const baseY =
					cy + Math.sin(b.angle) * b.orbitR * 0.35 + lY * b.orbitR * 0.25

				// 1) якорим центр к курсору (постоянное плавное смещение)
				const x0 = baseX + (mouse.x - baseX) * CURSOR_PULL
				const y0 = baseY + (mouse.y - baseY) * CURSOR_PULL

				// 2) магнит — сильнее тянет в радиусе влияния
				const dx = mouse.x - x0,
					dy = mouse.y - y0
				const dist = Math.hypot(dx, dy)
				const R = minSide * ATTR_RADIUS_K
				const k = dist < R ? Math.pow(1 - dist / R, 2) * ATTR_FORCE : 0
				const x = x0 + dx * k
				const y = y0 + dy * k

				// «дыхание» масштаба
				const pulse = (Math.sin(t * PULSE_SPEED + i * 0.9) + 1) / 2 // 0..1
				const scale = SCALE_MIN + (SCALE_MAX - SCALE_MIN) * pulse

				// локальный эллипс через матрицу трансформации
				ctx.setTransform(b.sx * scale, 0, 0, b.sy * scale, x, y)

				// плавное смещение цвета
				const hue = (b.hue0 + ((t * HUE_DRIFT) / 1000) * 6) % 360

				// большой мягкий градиент с прозрачным центром (только свечение)
				const radius = Math.max(w, h) * 0.35 * dpr
				const rOuter = radius
				const rInner = rOuter * RING_INNER_K // центр вдвое меньше прежнего

				const grad = ctx.createRadialGradient(0, 0, rInner, 0, 0, rOuter)
				grad.addColorStop(0.0, hsl(hue, 90, 55, 0.0)) // центр прозр.
				grad.addColorStop(0.45, hsl((hue + 8) % 360, 92, 58, 0.85))
				grad.addColorStop(0.85, hsl((hue + 24) % 360, 88, 55, 0.2))
				grad.addColorStop(1.0, hsl((hue + 24) % 360, 88, 55, 0.0))

				ctx.fillStyle = grad
				ctx.beginPath()
				ctx.arc(0, 0, rOuter, 0, TWO)
				ctx.fill()

				// сброс трансформа перед следующим пятном
				ctx.setTransform(1, 0, 0, 1, 0, 0)
			}

			rafId = requestAnimationFrame(frame)
		}
		rafId = requestAnimationFrame(frame)
		window.addEventListener('beforeunload', () => cancelAnimationFrame(rafId))
	}

	// ---------------- small utils ----------------
	const $ = (s, el = document) => el.querySelector(s)
	const $$ = (s, el = document) => Array.from(el.querySelectorAll(s))

	// Toast stack (многократные, авто-закрытие)
	const ensureToastsHost = () => {
		let host = $('#toasts')
		if (!host) {
			host = document.createElement('div')
			host.id = 'toasts'
			host.className = 'toasts'
			document.body.appendChild(host)
		}
		return host
	}
	function toast(message, type = 'success', timeout = 2500) {
		const host = ensureToastsHost()
		const t = document.createElement('div')
		t.className = `toast ${type}`
		t.textContent = message
		host.appendChild(t)
		requestAnimationFrame(() => t.classList.add('show'))
		const close = () => {
			t.classList.remove('show')
			setTimeout(() => t.remove(), 250)
		}
		setTimeout(close, timeout)
		return { close }
	}

	// Тема (dark/light) — сохраним в localStorage
	const THEME_KEY = 'mc_theme'
	const applyTheme = t => {
		document.documentElement.dataset.theme = t
	}
	const initTheme = () => {
		const saved = localStorage.getItem(THEME_KEY) || 'dark'
		applyTheme(saved)
		const btn = $('#theme-toggle')
		if (btn) {
			btn.addEventListener('click', () => {
				const next =
					(localStorage.getItem(THEME_KEY) || 'dark') === 'dark'
						? 'light'
						: 'dark'
				localStorage.setItem(THEME_KEY, next)
				applyTheme(next)
			})
		}
	}

	// Навигация: подсветка активного пункта
	const markActiveNav = () => {
		const path = location.pathname.replace(/\/+$/, '') || '/'
		$$('#nav a').forEach(a => {
			try {
				const href = new URL(a.href).pathname.replace(/\/+$/, '') || '/'
				if (href === path) a.classList.add('active')
			} catch (_) {}
		})
	}

	const tokenKey = 'mc_token'
	const selKey = 'mc_sel_by_type'
	const nameKey = 'mc_name_by_id'
	const setToken = t => localStorage.setItem(tokenKey, t)
	const getToken = () => localStorage.getItem(tokenKey)
	const authHeader = () =>
		getToken() ? { Authorization: 'Bearer ' + getToken() } : {}

	// правильная ссылка для скачивания (+ ?token= )
	const buildDownloadUrl = id =>
		`${API}/api/files/${encodeURIComponent(
			id
		)}/download?token=${encodeURIComponent(getToken() || '')}`

	const fmtKB = n => `${(n / 1024).toFixed(1)} KB`
	const short = (s, n = 8) => (s || '').slice(0, n)

	// хранение «имя файла по id» (после загрузки показываем человеческие названия)
	const NameCache = {
		getAll: () => JSON.parse(localStorage.getItem(nameKey) || '{}'),
		set: (id, name) => {
			const m = NameCache.getAll()
			m[id] = name
			localStorage.setItem(nameKey, JSON.stringify(m))
		},
		get: id => NameCache.getAll()[id],
	}

	// выбранные файлы по типам задач
	const SelectStore = {
		getAll: () => JSON.parse(localStorage.getItem(selKey) || '{}'),
		get: type => SelectStore.getAll()[type] || [],
		set: (type, ids) => {
			const m = SelectStore.getAll()
			m[type] = ids
			localStorage.setItem(selKey, JSON.stringify(m))
		},
	}

	// фильтры по MIME под задачи
	const FILTERS = {
		image: f => (f.mime || '').startsWith('image/'),
		audio: f => (f.mime || '').startsWith('audio/'),
		video: f => (f.mime || '').startsWith('video/'),
		document: f =>
			/(pdf|officedocument|msword|excel|spreadsheet|text\/csv)/.test(
				f.mime || ''
			),
		archive: f => /(zip|x-7z|rar)/.test(f.mime || ''),
		ocr: f =>
			(f.mime || '').startsWith('image/') || f.mime === 'application/pdf',
		translation: () => false,
		generation: () => false,
	}

	// ---------------- api helper ----------------
	const api = async (path, opts = {}) => {
		const res = await fetch(API + path, {
			...opts,
			headers: {
				'Content-Type': 'application/json',
				...authHeader(),
				...(opts.headers || {}),
			},
		})
		if (!res.ok) throw new Error('HTTP ' + res.status)
		const ct = res.headers.get('content-type') || ''
		return ct.includes('application/json') ? res.json() : res
	}

	// ---------------- auth ----------------
	const updateUserLabel = async () => {
		const label = $('#user-label')
		const btn = $('#btn-auth')
		if (!getToken()) {
			label && (label.textContent = 'Гость')
			btn && (btn.onclick = () => $('#auth-modal')?.classList.add('show'))
			return
		}
		try {
			const me = await api('/api/me')
			label && (label.textContent = me.email)
			btn && (btn.onclick = () => toast('Вы уже авторизованы'))
		} catch {
			label && (label.textContent = 'Гость')
			localStorage.removeItem(tokenKey)
		}
	}

	const bindAuth = () => {
		const modal = $('#auth-modal'),
			email = $('#auth-email'),
			pass = $('#auth-pass')
		$('#btn-close-auth')?.addEventListener('click', () =>
			modal?.classList.remove('show')
		)
		$('#btn-login')?.addEventListener('click', async () => {
			try {
				const r = await api('/api/auth/login', {
					method: 'POST',
					body: JSON.stringify({ email: email.value, password: pass.value }),
				})
				setToken(r.access_token)
				toast('Вход выполнен')
				modal?.classList.remove('show')
				updateUserLabel()
			} catch {
				toast('Ошибка входа', 'error')
			}
		})
		$('#btn-register')?.addEventListener('click', async () => {
			try {
				await api('/api/auth/register', {
					method: 'POST',
					body: JSON.stringify({ email: email.value, password: pass.value }),
				})
				const r = await api('/api/auth/login', {
					method: 'POST',
					body: JSON.stringify({ email: email.value, password: pass.value }),
				})
				setToken(r.access_token)
				toast('Аккаунт создан')
				modal?.classList.remove('show')
				updateUserLabel()
			} catch {
				toast('Ошибка регистрации', 'error')
			}
		})
	}

	// ---------------- files cache ----------------
	let filesCache = []
	let filesCacheAt = 0
	const loadFiles = async (force = false) => {
		const now = Date.now()
		if (!force && filesCache.length && now - filesCacheAt < 3000)
			return filesCache
		const r = await api('/api/files')
		filesCache = r.items || []
		filesCacheAt = now
		return filesCache
	}

	// ---------------- upload (drag & drop) ----------------
	const bindUpload = currentType => {
		const dz = $('#dropzone')
		if (!dz) return
		const input = $('#file-input'),
			choose = $('#btn-choose'),
			chips = $('#uploaded-chips')

		const addChip = (id, label, isImage) => {
			const el = document.createElement('span')
			el.className = 'file-chip'
			if (isImage) {
				const img = document.createElement('img')
				img.src = buildDownloadUrl(id)
				img.alt = ''
				img.width = 20
				img.height = 20
				img.loading = 'lazy'
				el.appendChild(img)
			}
			const txt = document.createElement('span')
			txt.textContent = label
			el.appendChild(txt)
			chips && chips.appendChild(el)
		}

		const uploadFile = async file => {
			const fd = new FormData()
			fd.append('file', file)
			const res = await fetch(API + '/api/files/upload', {
				method: 'POST',
				headers: { ...authHeader() },
				body: fd,
			})
			if (!res.ok) throw new Error()
			const j = await res.json()
			NameCache.set(j.file_id, file.name)
			await loadFiles(true)

			if (currentType && FILTERS[currentType]) {
				const f = filesCache.find(x => x.id === j.file_id)
				if (f && FILTERS[currentType](f)) {
					const sel = ensureFileSelect(currentType)
					selectIds(currentType, [
						...new Set([...getSelected(currentType), j.file_id]),
					])
					renderFileSelect(currentType, sel)
					renderChips(currentType)
				}
			}
			const lbl = `${file.name || j.file_id} (${j.mime || 'file'})`
			addChip(j.file_id, lbl, (j.mime || '').startsWith('image/'))
		}

		const onFiles = async fls => {
			for (const f of fls) {
				try {
					await uploadFile(f)
				} catch {
					toast('Ошибка загрузки ' + f.name, 'error')
				}
			}
			toast('Загрузка завершена')
		}
		dz.addEventListener('dragover', e => {
			e.preventDefault()
			dz.classList.add('drag')
		})
		dz.addEventListener('dragleave', () => dz.classList.remove('drag'))
		dz.addEventListener('drop', e => {
			e.preventDefault()
			dz.classList.remove('drag')
			onFiles(e.dataTransfer.files)
		})
		choose?.addEventListener('click', () => input?.click())
		input?.addEventListener('change', () => onFiles(input.files))
	}

	// ---------------- params form ----------------
	const buildParamsForm = async (type, grid) => {
		try {
			const { options: schema = {} } = await api('/api/options/' + type)
			grid.innerHTML = ''
			for (const [key, def] of Object.entries(schema)) {
				const wrap = document.createElement('div')
				wrap.className = 'param'
				const id = 'param-' + key
				const label = document.createElement('label')
				label.textContent = key
				wrap.appendChild(label)
				let input
				if (Array.isArray(def)) {
					input = document.createElement('select')
					def.forEach(v => {
						const o = document.createElement('option')
						o.value = String(v)
						o.textContent = String(v)
						input.appendChild(o)
					})
				} else if (
					typeof def === 'string' &&
					(def.includes('bool') || def === 'boolean')
				) {
					input = document.createElement('select')
					;['false', 'true'].forEach(v => {
						const o = document.createElement('option')
						o.value = v
						o.textContent = v
						input.appendChild(o)
					})
				} else {
					input = document.createElement('input')
					input.placeholder = String(def)
				}
				input.id = id
				wrap.appendChild(input)
				grid.appendChild(wrap)
			}
		} catch {
			toast('Не удалось загрузить параметры', 'error')
		}
	}

	// ---------------- file picker (multi-select + chips) ----------------
	const getSelected = type => SelectStore.get(type)
	const selectIds = (type, ids) => SelectStore.set(type, ids)

	const ensurePickerShell = () => {
		let shell = $('#file-select-wrap')
		if (!shell) {
			shell = document.createElement('div')
			shell.id = 'file-select-wrap'
			shell.innerHTML = `
        <div id="file-picker" style="margin:8px 0;">
          <div style="display:flex;gap:8px;align-items:center;">
            <input id="file-search" type="text" placeholder="Поиск по файлам..." style="flex:1;max-width:320px;padding:6px 8px;">
            <button id="file-refresh" class="btn ghost">Обновить</button>
            <button id="file-clear" class="btn ghost">Очистить выбор</button>
          </div>
          <select id="file-select" multiple size="6" style="width:100%;margin-top:8px;"></select>
        </div>
        <div id="file-chips" style="margin-top:6px;"></div>
        <input type="hidden" id="selected-file-ids" value="">
      `
			const anchor =
				$('#btn-add-files')?.parentElement ||
				$('[data-task-type]') ||
				document.body
			anchor.appendChild(shell)
		}
		return shell
	}

	const renderChips = type => {
		const chips = $('#file-chips')
		if (!chips) return
		const ids = getSelected(type)
		chips.innerHTML = ''
		ids.forEach(id => {
			const f = filesCache.find(x => x.id === id)
			const name =
				NameCache.get(id) ||
				(f ? `${short(id)}.${(f.mime || '').split('/')[1] || ''}` : id)
			const chip = document.createElement('span')
			chip.className = 'tag'
			if (f && (f.mime || '').startsWith('image/')) {
				const img = document.createElement('img')
				img.src = buildDownloadUrl(id)
				img.alt = ''
				img.width = 18
				img.height = 18
				img.loading = 'lazy'
				chip.appendChild(img)
			}
			const span = document.createElement('span')
			span.textContent = name
			chip.appendChild(span)
			const btn = document.createElement('button')
			btn.textContent = '×'
			btn.onclick = () => {
				const next = getSelected(type).filter(x => x !== id)
				selectIds(type, next)
				renderChips(type)
				renderFileSelect(type)
				updateHiddenSelected(type)
			}
			chip.appendChild(btn)
			chips.appendChild(chip)
		})
	}

	const updateHiddenSelected = type => {
		const hidden = $('#selected-file-ids')
		if (hidden) hidden.value = getSelected(type).join(',')
	}

	const renderFileSelect = async (type, selEl) => {
		await loadFiles(false)
		const sel = selEl || $('#file-select')
		if (!sel) return
		const srch = $('#file-search')?.value?.toLowerCase() || ''
		const onlyGood = FILTERS[type]
			? filesCache.filter(FILTERS[type])
			: filesCache.slice()
		const data = onlyGood.filter(f => {
			const label = (NameCache.get(f.id) || f.id || '') + ' ' + (f.mime || '')
			return !srch || label.toLowerCase().includes(srch)
		})
		sel.innerHTML = ''
		const selectedSet = new Set(getSelected(type))
		data.forEach(f => {
			const o = document.createElement('option')
			const name =
				NameCache.get(f.id) ||
				`${short(f.id)}.${(f.mime || '').split('/')[1] || ''}`
			o.value = f.id
			o.textContent = `${name} • ${f.mime || 'file'} • ${fmtKB(f.size || 0)}`
			o.selected = selectedSet.has(f.id)
			sel.appendChild(o)
		})
	}

	const ensureFileSelect = type => {
		const shell = ensurePickerShell()
		const sel = $('#file-select')
		if (!sel.dataset.bound) {
			sel.addEventListener('change', () => {
				const ids = Array.from(sel.selectedOptions).map(o => o.value)
				selectIds(type, ids)
				renderChips(type)
				updateHiddenSelected(type)
			})
			$('#file-search')?.addEventListener('input', () => renderFileSelect(type))
			$('#file-refresh')?.addEventListener('click', async () => {
				await loadFiles(true)
				renderFileSelect(type)
			})
			$('#file-clear')?.addEventListener('click', () => {
				selectIds(type, [])
				renderChips(type)
				renderFileSelect(type)
				updateHiddenSelected(type)
			})
			sel.dataset.bound = '1'
		}
		renderFileSelect(type, sel)
		renderChips(type)
		updateHiddenSelected(type)
		return sel
	}

	// ---------------- run task ----------------
	const bindRunTask = (type, btnRun) => {
		if (!btnRun) return
		btnRun.addEventListener('click', async () => {
			const params = {}
			$$('#params-grid input, #params-grid select').forEach(el => {
				const k = el.id.replace('param-', '')
				let v = el.value
				if (v === 'true') v = true
				else if (v === 'false') v = false
				params[k] = v
			})

			const needsFiles = [
				'image',
				'audio',
				'video',
				'document',
				'archive',
				'ocr',
			].includes(type)
			const fileIds = getSelected(type)
			if (needsFiles && (!fileIds || !fileIds.length)) {
				toast('Добавьте файлы подходящего типа', 'error')
				return
			}

			try {
				const r = await api('/api/tasks', {
					method: 'POST',
					body: JSON.stringify({ type, params, fileIds }),
				})
				toast('Задача создана: ' + r.task_id)
				const tb = $('#recent-tasks tbody')
				if (tb) {
					const tr = document.createElement('tr')
					tr.innerHTML = `<td>${r.task_id}</td><td>${type}</td><td>queued</td><td>0%</td><td>—</td>`
					tb.prepend(tr)
				}
			} catch {
				toast('Ошибка создания задачи', 'error')
			}
		})
	}

	// ---------------- skeleton helper ----------------
	const drawSkeletonTable = (tbody, cols = 6, rows = 5) => {
		if (!tbody) return
		tbody.innerHTML = Array.from({ length: rows })
			.map(
				() =>
					`<tr><td colspan="${cols}">
             <div class="skeleton"></div>
           </td></tr>`
			)
			.join('')
	}

	// ---------------- recent tasks (на вкладках с формой) ----------------
	const loadRecentTasks = async () => {
		const table = $('#recent-tasks tbody')
		if (!table) return
		drawSkeletonTable(table, 5, 4)
		try {
			const r = await api('/api/tasks')
			table.innerHTML = ''
			;(r.items || []).forEach(it => {
				const tr = document.createElement('tr')
				tr.innerHTML = `<td>${it.id}</td><td>${it.type}</td><td>${
					it.status
				}</td>
          <td>${it.progress || 0}%</td>
          <td><button class="btn ghost" data-results="${
						it.id
					}">Результаты</button></td>`
				table.appendChild(tr)
			})
			$$('button[data-results]', table).forEach(b => {
				b.addEventListener('click', () =>
					showTaskResults(b.getAttribute('data-results'))
				)
			})
		} catch {
			table.innerHTML = ''
		}
	}

	// ---------------- files page ----------------
	const bindFilesPage = () => {
		const tbody = $('#files-table tbody')
		if (!tbody) return
		const filterSel = $('#files-filter')
		const pass = (f, kind) => {
			if (!kind || kind === 'all') return true
			const map = {
				image: FILTERS.image,
				audio: FILTERS.audio,
				video: FILTERS.video,
				document: FILTERS.document,
				archive: FILTERS.archive,
			}
			return (map[kind] || (() => true))(f)
		}
		const render = items => {
			tbody.innerHTML = ''
			items.forEach(f => {
				const url = getToken() ? buildDownloadUrl(f.id) : '#'
				const downloadCell = getToken()
					? `<a class="btn ghost" href="${url}" download>Скачать</a>`
					: `<button class="btn ghost" data-need-auth>Скачать</button>`
				const tr = document.createElement('tr')
				tr.innerHTML = `<td><input type="checkbox" value="${f.id}"></td>
          <td>${f.id}</td><td>${f.mime}</td><td>${fmtKB(f.size || 0)}</td>
          <td>${f.created_at}</td><td>${downloadCell}</td>`
				tbody.appendChild(tr)
			})
			$$('button[data-need-auth]', tbody).forEach(b =>
				b.addEventListener('click', () =>
					toast('Войдите, чтобы скачивать файлы')
				)
			)
		}
		const load = async () => {
			drawSkeletonTable(tbody, 6, 6)
			const items = await loadFiles(true)
			const kind = filterSel?.value || 'all'
			render(items.filter(f => pass(f, kind)))
		}
		$('#refresh-files')?.addEventListener('click', load)
		$('#delete-selected-files')?.addEventListener('click', async () => {
			const ids = $$('#files-table tbody input[type="checkbox"]:checked').map(
				x => x.value
			)
			if (!ids.length) return toast('Ничего не выбрано')
			if (!confirm('Удалить выбранные файлы?')) return
			await api('/api/files/delete', {
				method: 'POST',
				body: JSON.stringify({ ids }),
			})
			toast('Удалено')
			load()
		})
		filterSel?.addEventListener('change', load)
		load()
	}

	// ---------------- tasks page ----------------
	const bindTasksPage = () => {
		const tbody = $('#tasks-table tbody')
		if (!tbody) return
		const row = t =>
			`<tr>
        <td><input type="checkbox" value="${t.id}"></td>
        <td>${t.id}</td>
        <td>${t.type}</td>
        <td>${t.status}</td>
        <td><div class="progress"><span style="width:${
					t.progress || 0
				}%"></span></div></td>
        <td>${t.created_at || ''}</td>
        <td><button class="btn ghost" data-results="${
					t.id
				}">Результаты</button></td>
      </tr>`
		const load = async () => {
			drawSkeletonTable(tbody, 7, 7)
			const r = await api('/api/tasks')
			tbody.innerHTML = (r.items || []).map(row).join('')
			$$('button[data-results]', tbody).forEach(b =>
				b.addEventListener('click', () =>
					showTaskResults(b.getAttribute('data-results'))
				)
			)
		}
		$('#refresh-tasks')?.addEventListener('click', load)
		$('#delete-selected-tasks')?.addEventListener('click', async () => {
			const ids = $$('#tasks-table tbody input[type="checkbox"]:checked').map(
				x => x.value
			)
			if (!ids.length) return toast('Ничего не выбрано')
			if (!confirm('Удалить выбранные задачи?')) return
			await api('/api/tasks/delete', {
				method: 'POST',
				body: JSON.stringify({ ids }),
			})
			toast('Удалено')
			load()
		})
		load()
		setInterval(load, 5000)
	}

	// ---------------- modal: task results ----------------
	const showTaskResults = async taskId => {
		try {
			const j = await api(`/api/tasks/${taskId}`)
			const outs = j.outputs || []
			if (!outs.length) return toast('Результатов пока нет', 'warning')
			let modal = $('#task-results-modal')
			if (!modal) {
				modal = document.createElement('div')
				modal.id = 'task-results-modal'
				modal.className = 'modal show'
				modal.innerHTML = `
          <section class="card" style="max-width:90vw;min-width:320px;">
            <div class="row" style="justify-content:space-between;align-items:center;margin-bottom:8px;">
              <h2 style="margin:0">Результаты задачи</h2>
              <button id="task-res-close" class="btn ghost">×</button>
            </div>
            <div id="task-res-body"></div>
          </section>`
				document.body.appendChild(modal)
				$('#task-res-close')?.addEventListener('click', () => modal.remove())
				modal.addEventListener('click', e => {
					if (e.target === modal) modal.remove()
				})
			}
			const body = $('#task-res-body', modal)
			body.innerHTML = outs
				.map(o => {
					const url = buildDownloadUrl(o.file_id)
					const isImg = (o.mime || '').startsWith('image/')
					const thumb = isImg
						? `<img src="${url}" alt="" loading="lazy" style="width:80px;height:80px;object-fit:cover;border-radius:8px;border:1px solid var(--border);margin-right:8px;">`
						: ''
					return `
          <div style="display:flex;align-items:center;justify-content:space-between;border:1px solid var(--border);border-radius:10px;padding:8px 10px;margin:6px 0;background:var(--panel-2)">
            <div style="display:flex;align-items:center;gap:8px;">
              ${thumb}
              <div>
                <div style="font-weight:600;">${o.file_id}</div>
                <div class="muted" style="font-size:12px;">${
									o.mime || ''
								} • ${fmtKB(o.size || 0)}</div>
              </div>
            </div>
            <a class="btn" href="${url}" download>Скачать</a>
          </div>`
				})
				.join('')
		} catch {
			toast('Не удалось получить результаты', 'error')
		}
	}

	// ---------------- settings ----------------
	const bindSettings = () => {
		$('#btn-logout')?.addEventListener('click', () => {
			localStorage.removeItem(tokenKey)
			toast('Выход выполнен')
			updateUserLabel()
		})
		$('#btn-save-profile')?.addEventListener('click', async () => {
			const body = { name: $('#profile-name')?.value || '' }
			const pass = $('#profile-pass')?.value
			if (pass) body.password = pass
			try {
				await api('/api/me', { method: 'PATCH', body: JSON.stringify(body) })
				toast('Профиль обновлён')
			} catch {
				toast('Ошибка сохранения', 'error')
			}
		})
		$$('button[data-clean]').forEach(b =>
			b.addEventListener('click', async () => {
				const action = b.getAttribute('data-clean')
				if (!confirm('Выполнить очистку: ' + action + '?')) return
				try {
					await api('/api/cleanup', {
						method: 'POST',
						body: JSON.stringify({ action }),
					})
					toast('Готово')
				} catch {
					toast('Ошибка очистки', 'error')
				}
			})
		)
	}

	// ---------------- boot ----------------
	document.addEventListener('DOMContentLoaded', async () => {
		initTheme()
		initAnimatedBackground()
		markActiveNav()
		bindAuth()
		updateUserLabel()

		const wrap = document.querySelector('[data-task-type]')
		const currentType = wrap?.getAttribute('data-task-type') || null

		// dnd загрузка — в контексте текущей вкладки
		bindUpload(currentType)

		if (wrap) {
			const grid = $('#params-grid')
			await buildParamsForm(currentType, grid)

			// мультиселект файлов, фильтрация по типу, сохранение выбора
			ensureFileSelect(currentType)

			// запуск задач
			bindRunTask(currentType, $('#btn-run-task'))

			// последние задачи (на странице типа image/audio/…)
			loadRecentTasks()
		}

		bindFilesPage()
		bindTasksPage()
		bindSettings()
	})
})()
