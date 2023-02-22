__version__ = "0.0.4"
__author__ = "Demod"

import zlib
import gc

try:
    from thumbyGrayscale import Sprite
    gs = True
except ImportError:
    from thumbySprite import Sprite
    gs = False

M_RAW = 0
M_PY = 1
M_IMG = 2

IMG_MASK = 0x01
IMG_GS = 0x02
IMG_FRAMES = 0x04


class ZPackFile:
    def __init__(self, file):
        gc.collect()

        zfs = open(file, 'rb')
        zds = zlib.DecompIO(zfs)
        mfs, _ = _rU32(zds.read(4), 0)
        mfd = zds.read(mfs)
        mfv = memoryview(mfd)
        self.entries = {}

        mfc, o = _rU8(mfv, 0)
        for i in range(mfc):
            k, o = _rStr(mfv, o)
            id, o = _rU8(mfv, o)
            dc, o = _rU8(mfv, o)
            d = []
            for j in range(dc):
                dl, o = _rU32(mfv, o)
                # print("memfree: "+str(gc.mem_free()))
                # print(k+"["+str(j)+"] "+str(dl)+" bytes")
                dba = bytearray(dl)
                zds.readinto(dba)
                d.append(dba)
            e = ZPackEntry(id, d)
            if id == M_IMG:
                flags, o = _rU8(mfv, o)
                e.m = (flags & IMG_MASK) > 0
                e.gs = (flags & IMG_GS) > 0
                e.f = (flags & IMG_FRAMES) > 0
                e.w, o = _rU16(mfv, o)
                e.h, o = _rU16(mfv, o)
                if e.f:
                    e.fc, o = _rU8(mfv, o)
            elif id == M_PY:
                e.n, o = _rStr(mfv, o)
            self.entries[k] = e

        zfs = None
        zds = None
        mfd = None
        gc.collect()

    def metadata(self, key):
        return self.entries[key]

    def raw(self, key):
        e = self.entries[key]
        return e.d[0]

    def bitmap(self, key):
        e = self.entries[key]
        if e.id == M_IMG:
            if gs and e.gs:
                return _frames((e.d[0], e.d[1]), e.w, e.h) if e.f else (e.d[0], e.d[1])
            else:
                return _frames(e.d[0], e.w, e.h) if e.f else e.d[0]

    def mask(self, key):
        e = self.entries[key]
        if e.id == M_IMG and e.m:
            if e.gs:
                return _frames(e.d[2], e.w, e.h) if e.f else e.d[2]
            else:
                return _frames(e.d[1], e.w, e.h) if e.f else e.d[1]

    def bitmapAndMask(self, key):
        return (self.bitmap(key), self.mask(key))

    def sprite(self, key):
        e = self.entries[key]
        if e.id == M_IMG:
            if gs and e.gs:
                return Sprite(e.w, e.h, (e.d[0], e.d[1]))
            else:
                return Sprite(e.w, e.h, e.d[0])
            return

    def spriteMask(self, key):
        e = self.entries[key]
        if e.id == M_IMG and e.m:
            if e.gs:
                return Sprite(e.w, e.h, e.d[2])
            else:
                return Sprite(e.w, e.h, e.d[1])

    def spriteAndMask(self, key):
        return (self.sprite(key), self.spriteMask(key))

    def python(self, key):
        # TODO
        pass


class ZPackEntry:
    def __init__(self, id, d):
        self.id = id
        self.d = d


def _rU8(mv, o):
    return mv[o], o+1


def _rU16(mv, o):
    return (mv[o] << 8) + mv[o+1], o+2


def _rU32(mv, o):
    return (mv[o] << 24) + (mv[o+1] << 16) + (mv[o+2] << 8) + mv[o+3], o+4


def _rStr(mv, o):
    l, o = _rU16(mv, o)
    return str(mv[o:o+l], 'utf8'), o+l


def _frames(bmp, w, h):
    bgs = isinstance(bmp, (tuple, list))
    bal = len(bmp[0]) if bgs else len(bmp)
    bh = (h + 7) // 8
    fl = w * bh
    fc = bal // fl
    if bgs:
        mv1 = memoryview(bmp[0])
        mv2 = memoryview(bmp[1])
        return [(mv1[i*fl:(i+1)*fl], mv2[i*fl:(i+1)*fl]) for i in range(fc)]
    else:
        mv = memoryview(bmp)
        return [mv[i*fl:(i+1)*fl] for i in range(fc)]
