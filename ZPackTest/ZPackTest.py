import math
from thumbyButton import buttonA, buttonB, buttonU, buttonD, buttonL, buttonR
from ZPack import ZPackFile
from sys import path as syspath  # NOQA
syspath.insert(0, '/Games/ZPackTest')  # NOQA


GRAYSCALE = True
if GRAYSCALE:
    from thumbyGrayscale import display
else:
    from thumbyGraphics import display

display.setFPS(30)

zpArt = ZPackFile("/Games/ZPackTest/Art.zpack")

bg = [
    zpArt.bitmapAndMask("bg0"),
    zpArt.bitmapAndMask("bg2"),
    zpArt.bitmapAndMask("bg1"),
    zpArt.bitmapAndMask("bg3"),
    zpArt.bitmapAndMask("bg4"),
    zpArt.bitmapAndMask("bg5")
]
bgParallax = [
    [0.0, 0.25],
    [0.05, 0.3],
    [0.1, 0.2],
    [0.15, 0.2],
    [1.0, 1.0],
    [1.6, 1.0]
]
bgRect = [
    [0, -40, 72, 72],
    [0, -16, 128, 56],
    [0, -28, 128, 24],
    [0, 4, 128, 40],
    [0, 24, 128, 16],
    [0, 32, 128, 8]
]

player, playerMask = zpArt.spriteAndMask("char")

player.x = 4
player.y = 5
player.setFrame(17)
playerMask.setFrame(17)

idleAnim = [[32, 150], [8, 4], [7, 30], [31, 30], [15, 60]]
walkAnim = [[17, 6], [16, 6], [17, 6], [18, 6]]
runAnim = [[20, 4], [19, 4], [20, 4], [21, 4]]
flyUpAnim = [[73, 4], [63, 100000]]
flyIdleAnim = [[27, 4], [90, 100000]]
flySlowAnim = [[27, 4], [20, 100000]]
flyFastAnim = [[26, 4], [19, 100000]]
flyDownAnim = [[27, 4], [26, 100000]]

stateAnims = [idleAnim, walkAnim, runAnim,
              flyUpAnim, flyUpAnim, flyUpAnim,
              flyIdleAnim, flySlowAnim, flyFastAnim,
              flyDownAnim, flyDownAnim, flyDownAnim]
stateX = 1
stateY = 0
state = 1
lastState = -1
anim = walkAnim
animIndex = -1
animNext = 0

stateVelX = [0, 1, 2]

posX = 0
posY = 0

floatFrame = 0

while True:
    posX += stateVelX[stateX]

    if buttonL.justPressed():
        stateX = max(0, stateX - 1)

    if buttonR.justPressed():
        stateX = min(2, stateX + 1)

    if buttonU.pressed() and posY < 112:
        posY = min(112, posY + 1)
        stateY = 1
    elif buttonD.pressed() and posY > 0:
        posY = max(0, posY - 2)
        stateY = 3
    else:
        stateY = 0 if posY == 0 else 2

    state = stateY * 3 + stateX

    if state != lastState:
        lastState = state
        anim = stateAnims[state]
        animIndex = -1
        animNext = 0

    animNext -= 1
    if animNext <= 0:
        animIndex = (animIndex + 1) % len(anim)
        animNext = anim[animIndex][1]
        frame = anim[animIndex][0]
        player.setFrame(frame)
        playerMask.setFrame(frame)

    if stateY == 2:
        floatFrame += 1
        player.y = 5 + round(1.5*math.sin(floatFrame/8))
    else:
        player.y = 5

    for i in range(6):
        parallax = bgParallax[i]
        rect = bgRect[i]
        w = rect[2]
        h = rect[3]
        x = rect[0] - (int(posX * parallax[0]) % w)
        y = rect[1] + int(posY * parallax[1])
        bmp = bg[i][0]
        mask = bg[i][1]
        if mask is None:
            display.blit(bmp, x, y, w, h, -1, 0, 0)
            if x + w < 72:
                display.blit(bmp, x + w, y, w, h, -1, 0, 0)
        else:
            display.blitWithMask(
                bmp, x, y, w, h, -1, 0, 0, mask)
            if x + w < 72:
                display.blitWithMask(bmp, x + w, y, w, h, -1, 0, 0, mask)

        if i == 4:
            display.drawSpriteWithMask(player, playerMask)

    display.update()
