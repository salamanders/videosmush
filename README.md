# videosmush

Time-lapse of multiple videos using scripted frame blending to adjust the speedup

1. Put ordered video clips into the "input" folder.
2. Create a "script.csv"
3. Run and wait.

The script is a series of lines like

```csv
213220,4
217366,14
```

which is "frame 213220 should show up at 00:04 seconds into the video (which is a huge speedup),
frame 217366 should be at 00:14 seconds (which is much less of a speedup)
etc."

The app will calculate the right amount of frames to blend together to hit the target.
It is slow to avoid "binning" in the blended frames.