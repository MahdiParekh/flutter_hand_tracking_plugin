import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';
import 'package:flutter_hand_tracking_plugin/HandGestureRecognition.dart';
import 'package:flutter_hand_tracking_plugin/flutter_hand_tracking_plugin.dart';
import 'package:flutter_hand_tracking_plugin/gen/landmark.pb.dart';

void main() => runApp(MaterialApp(home: MyApp()));

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  HandTrackingViewController _controller;
  Gestures _gesture;

  Color _selectedColor = Colors.black;
  Color _pickerColor = Colors.black;
  double _opacity = 1.0;
  double _strokeWidth = 3.0;
  double _canvasHeight = 300;
  double _canvasWeight = 300;

  bool _showBottomList = false;
  List<DrawingPoints> _points = List();
  SelectedMode _selectedMode = SelectedMode.StrokeWidth;

  List<Color> _colors = [
    Colors.red,
    Colors.green,
    Colors.blue,
    Colors.amber,
    Colors.black
  ];

  void continueDraw(landmark) => setState(() => _points.add(DrawingPoints(
      points: Offset(landmark.x * _canvasWeight, landmark.y * _canvasHeight),
      paint: Paint()
        ..strokeCap = StrokeCap.butt
        ..isAntiAlias = true
        ..color = _selectedColor.withOpacity(_opacity)
        ..strokeWidth = _strokeWidth)));

  void finishDraw() => setState(() => _points.add(null));

  void _onLandMarkStream(List landmarkList) {
  print(landmarkList);
  }

  getColorList() {
    List<Widget> listWidget = List();
    for (Color color in _colors) {
      listWidget.add(colorCircle(color));
    }
    Widget colorPicker = GestureDetector(
      onTap: () {
        showDialog(
          context: context,
          builder:(BuildContext context){
            return  AlertDialog(
              title: const Text('选择颜色'),
              content: SingleChildScrollView(
                child: ColorPicker(
                  pickerColor: _pickerColor,
                  onColorChanged: (color) => _pickerColor = color,
//                enableLabel: true,
                  pickerAreaHeightPercent: 0.8,
                ),
              ),
              actions: <Widget>[
                FlatButton(
                  child: const Text('保存'),
                  onPressed: () {
                    setState(() => _selectedColor = _pickerColor);
                    Navigator.of(context).pop();
                  },
                ),
              ],
            );
          }
        );
      },
      child: ClipOval(
        child: Container(
          padding: const EdgeInsets.only(bottom: 16.0),
          height: 36,
          width: 36,
          decoration: BoxDecoration(
              gradient: LinearGradient(
            colors: [Colors.red, Colors.green, Colors.blue],
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
          )),
        ),
      ),
    );
    listWidget.add(colorPicker);
    return listWidget;
  }

  Widget colorCircle(Color color) {
    return GestureDetector(
      onTap: () => setState(() => _selectedColor = color),
      child: ClipOval(
        child: Container(
          padding: const EdgeInsets.only(bottom: 16.0),
          height: 36,
          width: 36,
          color: color,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Hand Tracking Example App'),
      ),
      body: SingleChildScrollView(
        child: Column(
          children: <Widget>[
            Container(
              height: MediaQuery.of(context).size.height,
              child: HandTrackingView(
                onViewCreated: (HandTrackingViewController c) => setState(() {
                  _controller = c;
                  if (_controller != null)
                    _controller.landMarksStream.listen(_onLandMarkStream);
                }),
              ),
            ),

          ],
        ),
      ),

    );
  }
}

class DrawingPainter extends CustomPainter {
  DrawingPainter({this.pointsList});

  List<DrawingPoints> pointsList;
  List<Offset> offsetPoints = List();

  @override
  void paint(Canvas canvas, Size size) {
    for (int i = 0; i < pointsList.length - 1; i++) {
      if (pointsList[i] != null && pointsList[i + 1] != null) {
        canvas.drawLine(pointsList[i].points, pointsList[i + 1].points,
            pointsList[i].paint);
      } else if (pointsList[i] != null && pointsList[i + 1] == null) {
        offsetPoints.clear();
        offsetPoints.add(pointsList[i].points);
        offsetPoints.add(Offset(
            pointsList[i].points.dx + 0.1, pointsList[i].points.dy + 0.1));
        canvas.drawPoints(PointMode.points, offsetPoints, pointsList[i].paint);
      }
    }
  }

  @override
  bool shouldRepaint(DrawingPainter oldDelegate) => true;
}

class DrawingPoints {
  Paint paint;
  Offset points;

  DrawingPoints({this.points, this.paint});
}

enum SelectedMode { StrokeWidth, Opacity, Color }
