#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform float radius;
uniform vec2 res;
uniform float t, life, voice, activity, strength;

// Small-domain hash avoids the old large sin()*43758 values collapsing under mediump.
float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * .1031);
    q += dot(q, q.yzx + 19.19);
    return fract((q.x + q.y) * q.z);
}
float noise(vec2 p) {
    vec2 i=floor(p), f=fract(p); f=f*f*(3.-2.*f);
    return mix(mix(hash(i),hash(i+vec2(1.,0.)),f.x),
               mix(hash(i+vec2(0.,1.)),hash(i+vec2(1.,1.)),f.x),f.y);
}
float fbm(vec2 p) {
    float v=0., amp=.5;
    for(int i=0;i<4;i++) {
        v+=amp*noise(p);
        p=mat2(.8,-.6,.6,.8)*p*2.03+vec2(2.7,4.1);
        amp*=.5;
    }
    return v;
}
void main() {
    vec2 uv=(gl_FragCoord.xy-.5*res)/res.x;
    float r=length(uv), a=atan(uv.y,uv.x);
    vec2 dir=vec2(cos(a),sin(a));
    float time=t*(.23+activity*.1);
    // Periodic angular coordinates: no seam at -pi/pi, no sudden random jumps.
    vec2 drift=vec2(time*.21,-time*.17);
    float n=fbm(dir*2.1+drift);
    float folds=fbm(uv*6.2+vec2(n*3.,time*.24));
    float organic=life*(.034*sin(a*3.+time*.63)+.016*sin(a*5.-time*.43)+.052*(n-.5));
    float edge=radius+organic+voice*.014;
    float width=.0025+life*(.011+.018*folds);
    float core=exp(-pow((r-edge)/width,2.));
    float veil=exp(-pow((r-edge)/(.018+.080*life),2.))*(.16+.32*folds);
    float strands=0.;
    for(int i=0;i<7;i++) {
        float k=float(i);
        float speed=.19+hash(vec2(k,7.))*.43;
        float field=noise(dir*(2.4+k*.33)+vec2(time*speed,k*9.7));
        float wandering=(field-.5)*.050+sin(a*(2.+mod(k,4.))+time*speed+k*2.13)*.009;
        float strandEdge=edge+life*((k-3.)*.007+wandering);
        float strandWidth=.0018+life*(.0015+.003*hash(vec2(k,4.)));
        float filament=exp(-pow((r-strandEdge)/strandWidth,2.));
        float segments=.16+.84*pow(noise(dir*(4.+k*.47)+vec2(k*4.3,-time*speed)),1.5);
        strands+=filament*segments*(.16+.15*hash(vec2(k,2.)));
    }
    float arcLight=.48+.52*fbm(dir*2.6+vec2(time*.23,time*.14));
    float glow=(core*(.24+.36*folds)+veil*life+strands*life)*arcLight;
    // Start as the same narrow circular outline, then grow depth and irregular strands.
    glow*=smoothstep(0.,.18,life)*strength*(1.+voice*.8);
    vec3 teal=vec3(131.,245.,208.)/255.;
    vec3 col=vec3(3.,5.,4.)/255.+teal*glow;
    float grain=(hash(gl_FragCoord.xy)-.5)*.014;
    col+=grain*min(1.,glow*2.);
    gl_FragColor=vec4(col,1.);
}
