# Thuật toán âm lịch Việt Nam (Hồ Ngọc Đức), múi giờ +7
import math, datetime
PI=math.pi
def jd_from_date(dd,mm,yy):
    a=(14-mm)//12; y=yy+4800-a; m=mm+12*a-3
    jd=dd+(153*m+2)//5+365*y+y//4-y//100+y//400-32045
    if jd<2299161: jd=dd+(153*m+2)//5+365*y+y//4-32083
    return jd
def jd_to_date(jd):
    return datetime.date.fromordinal(jd-1721425)
def new_moon(k):
    T=k/1236.85; T2=T*T; T3=T2*T; dr=PI/180
    Jd1=2415020.75933+29.53058868*k+0.0001178*T2-0.000000155*T3
    Jd1+=0.00033*math.sin((166.56+132.87*T-0.009173*T2)*dr)
    M=359.2242+29.10535608*k-0.0000333*T2-0.00000347*T3
    Mpr=306.0253+385.81691806*k+0.0107306*T2+0.00001236*T3
    F=21.2964+390.67050646*k-0.0016528*T2-0.00000239*T3
    C1=(0.1734-0.000393*T)*math.sin(M*dr)+0.0021*math.sin(2*dr*M)
    C1-=0.4068*math.sin(Mpr*dr)-0.0161*math.sin(dr*2*Mpr)
    C1-=0.0004*math.sin(dr*3*Mpr)
    C1+=0.0104*math.sin(dr*2*F)-0.0051*math.sin(dr*(M+Mpr))
    C1-=0.0074*math.sin(dr*(M-Mpr))-0.0004*math.sin(dr*(2*F+M))
    C1-=0.0004*math.sin(dr*(2*F-M))-0.0006*math.sin(dr*(2*F+Mpr))
    C1+=0.0010*math.sin(dr*(2*F-Mpr))+0.0005*math.sin(dr*(2*Mpr+M))
    if T<-11: dt=0.001+0.000839*T+0.0002261*T2-0.00000845*T3-0.000000081*T*T3
    else: dt=-0.000278+0.000265*T+0.000262*T2
    return Jd1+C1-dt
def get_new_moon_day(k,tz): return int(new_moon(k)+0.5+tz/24)
def sun_longitude(jdn):
    T=(jdn-2451545.0)/36525; T2=T*T; dr=PI/180
    M=357.52910+35999.05030*T-0.0001559*T2-0.00000048*T*T2
    L0=280.46645+36000.76983*T+0.0003032*T2
    DL=(1.914600-0.004817*T-0.000014*T2)*math.sin(dr*M)
    DL+=(0.019993-0.000101*T)*math.sin(dr*2*M)+0.000290*math.sin(dr*3*M)
    L=(L0+DL)*dr; L=L-PI*2*int(L/(PI*2))
    if L<0: L+=2*PI
    return L
def get_sun_longitude(dayNumber,tz): return int(sun_longitude(dayNumber-0.5-tz/24)/PI*6)
def lunar_month11(yy,tz):
    off=jd_from_date(31,12,yy)-2415021
    k=int(off/29.530588853); nm=get_new_moon_day(k,tz)
    if get_sun_longitude(nm,tz)>=9: nm=get_new_moon_day(k-1,tz)
    return nm
def leap_month_offset(a11,tz):
    k=int((a11-2415021.076998695)/29.530588853+0.5); last=0; i=1
    arc=get_sun_longitude(get_new_moon_day(k+i,tz),tz)
    while True:
        last=arc; i+=1; arc=get_sun_longitude(get_new_moon_day(k+i,tz),tz)
        if not(arc!=last and i<14): break
    return i-1
def lunar_to_solar(ld,lm,ly,leap=0,tz=7):
    if lm<11: a11=lunar_month11(ly-1,tz); b11=lunar_month11(ly,tz)
    else: a11=lunar_month11(ly,tz); b11=lunar_month11(ly+1,tz)
    k=int(0.5+(a11-2415021.076998695)/29.530588853); off=lm-11
    if off<0: off+=12
    if b11-a11>365:
        lo=leap_month_offset(a11,tz); lpm=lo-2
        if lpm<0: lpm+=12
        if leap and lm!=lpm: return None
        if leap or off>=lo: off+=1
    ms=get_new_moon_day(k+off,tz)
    return jd_to_date(ms+ld-1)
if __name__=="__main__":
    import sys
    for y in (2026,2027):
        t=lunar_to_solar(1,1,y); g=lunar_to_solar(10,3,y)
        # ngày cuối tháng Chạp năm trước
        print(y,"Tet mung1",t,t.strftime('%a'),"| 29/12 nam truoc",lunar_to_solar(29,12,y-1),"| 30/12?",lunar_to_solar(30,12,y-1),"| Gio To",g,g.strftime('%a'))
