- Use .claude/*
- Use .agent/*
- Read TECHSPEC.md and follow the rule

# TASK 1
About project PUBG fun battle

I have maps in the /composeResources
What I want is to implement the same but super basic implementation maps in PUBG, which calculates distance

In pubg buy default
1000×1000 m is the size of one large grid square, not the entire map. Maps themselves come in different sizes:

Erangel, Miramar, Taego, Deston, Rondo — 8×8 km (i.e. 64 yellow squares)
Vikendi — 6×6 km
Sanhok — 4×4 km
Paramo — 3×3 km
Karakin — 2×2 km
Haven — 1×1 km (this one is actually one square)

The logic is simple: a yellow square always = 1 km, a white square = 100 m, and how many of these squares fit depends on the map. Therefore, the call "enemy in E4" on Erangel and Sanhok refers to physically different squares, but the size of the square itself is the same.

Build zomable component like in this structure
Box {
    Box { 
        Image {} // Image of selected map
        // Some kinda custom compoent. Map logic with zoomability, also scrollable by touch and mouse press touch
    }
    ScrollableTabRow  // Scrollable tabrow with list of strings with map name (aligned TopCenter)
    Zoom +- // Zoom logic plus minus
}

# TASK 2
I need overlay draw line component above my
By single click or touch it creates dot A, by second touch it draws B dot, between them auto-creates line, after second dot created.
And in this it should calculate distance between A and B, which suits to PUBG
